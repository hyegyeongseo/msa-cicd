## 목적

MSA 환경에서 서비스 간 흐름과 Docker Compose 기반 로컬 배포 구조를 이해하기 위한 학습 프로젝트입니다.

## 실행

```bash
docker compose up -d
```

## 아키텍처

```mermaid
graph LR

  %% Frontend Layer
  Frontend["Frontend (Nginx)"]

  %% Services
  UserService["User Service (Spring Boot)"]
  OrderService["Order Service (Spring Boot)"]

  %% Databases
  UserDB[(user-db MySQL)]
  OrderDB[(order-db MySQL)]
  Redis[(Redis Cache)]

  %% Flow
  Frontend --> UserService
  UserService --> OrderService

  UserService --> UserDB
  OrderService --> OrderDB
  OrderService --> Redis

  %% Styling hints (optional readability)
  classDef service fill:#e3f2fd,stroke:#1e88e5,stroke-width:1px;
  classDef db fill:#fff3e0,stroke:#fb8c00,stroke-width:1px;
  classDef cache fill:#f3e5f5,stroke:#8e24aa,stroke-width:1px;

  class UserService,OrderService service;
  class UserDB,OrderDB db;
  class Redis cache;
```


## 한계 인지

- 서비스 간 동기 호출로 인해 강한 결합 발생
- Order Service 장애 시 User Service에도 영향 전파 가능
- 분산 환경에서 데이터 정합성 보장 어려움

## 향후 방향

MSA 구조의 결합도를 낮추고, 서비스 간 독립성을 강화하기 위해
Kafka 기반 이벤트 드리븐 아키텍처로 전환 예정입니다.
