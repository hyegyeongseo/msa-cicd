# 배포 검증과 롤백

VM 배포(Jenkins → Ansible → Docker Compose)가 제대로 됐는지 확인하는 방법과, 실패했을 때의 동작을 정리한다.

## 1. 지금 어떤 버전이 떠 있나

VM에 접속해서 확인한다.

```bash
ssh ubuntu@192.168.237.134
cd /opt/msa
sudo docker compose ps          # IMAGE 열 = 이미지 태그, CREATED 열 = 컨테이너 생성 시각
grep IMAGE_TAG .env             # Ansible 이 기록한 태그
```

- 태그는 커밋의 짧은 SHA 다. `git log --oneline` 과 대조하면 어느 코드가 떠 있는지 알 수 있다.
- `docker compose up -d` 는 설정이 바뀐 컨테이너만 새로 만든다. 그래서 배포 후에는 앱 3개(frontend, user-service, order-service)의
  CREATED 만 최근이고, Kafka/Redis/MySQL 은 그대로다.

## 2. 헬스체크 (playbook 이 배포할 때마다 자동 수행)

| 대상 | 방법 | 통과 조건 | 최대 대기 |
|---|---|---|---|
| user-service | VM 안에서 `GET 127.0.0.1:8081/order` | 405 | 150초 |
| order-service | VM 안에서 `GET 127.0.0.1:8082/order` | 405 | 150초 |
| frontend | VM 안에서 `GET 127.0.0.1:3000/` | 200 | 60초 |

앱에 Actuator 가 없어서, POST 전용인 `/order` 에 GET 을 보내면 405 가 돌아오는 것으로 "Spring 이 떠 있다"를 판단한다.
헬스체크가 하나라도 실패하면 배포는 실패로 처리되고 롤백된다(아래 4번).

## 3. 동작 확인 (수동)

```bash
# 주문 요청 - 브라우저의 Order Product 버튼과 같은 요청
curl -X POST http://192.168.237.134:3000/api/users/order \
  -H "Content-Type: application/json" -d '{"username":"test","product":"item"}'

# Saga 가 끝났는지: status 가 REQUESTED 에서 "완료"로 바뀌어야 한다 (VM 에서)
cd /opt/msa && set -a && . ./.env && set +a
sudo docker compose exec -T user-db mysql -u"$USER_DB_USER" -p"$USER_DB_PASSWORD" user_db \
  -e "SELECT username, product, status FROM user_orders;"
```

외부에 열려 있는 포트는 frontend 의 3000 뿐이어야 한다. DB(3306), Redis(6379), Kafka(9092), 서비스(8081/8082)는
VM 밖에서 접속되지 않아야 한다.

## 4. 실패 시 롤백

```
새 버전 배포 → (pull 실패 | 컨테이너 기동 실패 | 헬스체크 실패)
   → 실패 원인 수집(컨테이너 상태, 앱 로그 일부)
   → 배포 직전에 떠 있던 버전으로 되돌림 (VM 의 .env 에 적혀 있던 IMAGE_TAG)
   → playbook 을 실패로 종료 → Jenkins 빌드도 실패(빨간색)
```

이전 버전은 별도 상태 파일 없이 VM 의 `.env` 에서 읽는다. 구현은 `ansible/deploy.yml` 의 `block/rescue` 다.

### 검증 결과

| 시나리오 | 만든 방법 | 결과 |
|---|---|---|
| A. 존재하지 않는 태그 | `-e image_tag=does-not-exist` 로 수동 실행 | pull 실패 → 롤백 → 종료 코드 2. 실행 중인 컨테이너는 건드리지 않았고 서비스 정상 |
| B. 기동에 실패하는 이미지 | `nginx.conf` 에 잘못된 지시어를 넣은 커밋을 push (테스트 후 revert) | Jenkins 가 자동으로 빌드 → frontend 헬스체크 실패 → 이전 버전으로 롤백 → 빌드 FAILURE. 로그에 `unknown directive` 가 남음 |

### 알려진 한계

- 컨테이너를 통째로 교체하므로 배포 중 수십 초 동안 서비스가 끊긴다 (무중단 배포 아님).
- 이전 버전 판단은 `.env` 의 값이므로, 첫 배포에는 롤백할 대상이 없다.
- DB 스키마는 `ddl-auto: update` 라서 롤백해도 되돌아가지 않는다.
- `docker-compose.yml` 자체가 깨졌다면 롤백에도 같은 파일이 쓰이므로 복구되지 않는다.
- Jenkinsfile 이 배포 전에 `latest` 태그를 push 한다. 배포에 실패한 빌드도 `latest` 를 덮어쓴다
  (배포는 항상 SHA 태그를 쓰므로 동작에는 영향이 없다).
- 이 검증은 헬스체크가 잡는 실패만 다룬다. 기동은 되지만 잘못 동작하는 경우는 잡지 못한다.
