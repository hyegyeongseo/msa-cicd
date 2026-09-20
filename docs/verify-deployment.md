# 배포 검증과 롤백

VM 배포(Jenkins → Ansible → Docker Compose)가 제대로 됐는지 확인하는 방법과, 실패했을 때의 동작을 정리한다.

## 1. 지금 어떤 버전이 떠 있나

VM에 접속해서 확인한다.

```bash
ssh ubuntu@192.168.237.134
cd /opt/msa
sudo docker compose ps          # IMAGE 열 = 이미지 태그, CREATED 열 = 컨테이너 생성 시각
grep IMAGE_TAG .env             # 지금 compose 가 쓰는 태그 (Ansible 이 기록)
sudo cat releases/current_tag   # 마지막으로 정상 배포된 태그 (배포가 성공했을 때만 갱신)
sudo cat releases/previous_tag  # 그 직전 정상 버전 (수동으로 되돌릴 때 참고)
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
   → 마지막으로 정상 배포된 버전(releases/current_tag)으로 되돌림
   → playbook 을 실패로 종료 → Jenkins 빌드도 실패(빨간색)
```

구현은 `ansible/deploy.yml` 의 `block/rescue` 다.

- 롤백 대상은 VM 의 `/opt/msa/releases/current_tag` 에서 읽는다. 이 파일은 **배포가 끝까지 성공했을 때만** 갱신되고,
  그때 이전 값은 `previous_tag` 로 옮겨진다. 그래서 배포 도중에 끊겨서 `.env` 가 어긋나도 "마지막 정상 버전"은 남는다.
- 이 기능을 넣기 전부터 떠 있던 VM 은 파일이 없으므로, 첫 한 번만 `.env` 의 값으로 대신하고 그 뒤로는 파일을 쓴다.
- Docker Hub 의 `latest` 태그는 배포(헬스체크 포함)가 성공한 뒤의 `Promote latest` stage 에서만 올린다.
  배포에 실패한 빌드는 그 stage 까지 가지 않으므로 `latest` 는 마지막 성공 버전을 가리킨다.

### 검증 결과

| 시나리오 | 만든 방법 | 결과 |
|---|---|---|
| A. 존재하지 않는 태그 | `-e image_tag=does-not-exist` 로 수동 실행 | pull 실패 → 롤백 → 종료 코드 2. 실행 중인 컨테이너는 건드리지 않았고 서비스 정상 |
| B. 기동에 실패하는 이미지 | `nginx.conf` 에 잘못된 지시어를 넣은 커밋을 push (테스트 후 revert) | Jenkins 가 자동으로 빌드 → frontend 헬스체크 실패 → 이전 버전으로 롤백 → 빌드 FAILURE. 로그에 `unknown directive` 가 남음 |
| C. 깨진 커밋 + `latest`/`current_tag` | B 와 같은 방법으로 다시 push (Jenkins 자동 실행) | 빌드 FAILURE. `Promote latest` stage 는 `skipped due to earlier failure(s)` 로 건너뜀. Docker Hub 의 frontend `latest` digest 는 그대로(`7aadcdee…`)이고 깨진 빌드 태그는 다른 digest(`7a0f5497…`). VM 의 `current_tag`/`previous_tag` 는 유지되고 `.env` 만 이전 버전으로 복구됨 |
| D. `.env` 가 망가진 상태 | VM 의 `.env` 태그를 일부러 `bogus` 로 바꾼 뒤 존재하지 않는 태그로 수동 배포 | 롤백 대상이 `.env` 가 아닌 `current_tag` 에서 읽혀 정상 버전으로 롤백됨 (`.env` 가 복구되고 서비스 정상) |

### 알려진 한계

- 컨테이너를 통째로 교체하므로 배포 중 수십 초 동안 서비스가 끊긴다 (무중단 배포 아님).
- 첫 배포(VM 에 아무 기록도 없는 상태)에는 롤백할 대상이 없다.
- DB 스키마는 `ddl-auto: update` 라서 롤백해도 되돌아가지 않는다.
- `docker-compose.yml` 자체가 깨졌다면 롤백에도 같은 파일이 쓰이므로 복구되지 않는다.
- 이 검증은 헬스체크가 잡는 실패만 다룬다. 기동은 되지만 잘못 동작하는 경우는 잡지 못한다.
