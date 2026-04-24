# used-trade

> 중고거래 플랫폼 백엔드 — 포트폴리오 프로젝트
>
> Spring Boot 3 · Java 21 · MySQL 8 · Redis 7 · Docker · AWS

## 프로젝트 목적

주니어 백엔드 개발자의 취업 포트폴리오로, 단순 CRUD 가 아닌 **동시성 제어**,
**분산 트랜잭션**, **멀티 서버 실시간 통신** 에 대한 기술적 트레이드오프와
Before/After 성능 수치를 근거로 제시하는 것을 목표로 한다.

## 핵심 ADR (Architecture Decision Record)

| # | 의사결정 | 선택 | 대안 | 근거 |
|---|---|---|---|---|
| 1 | 아키텍처 | Modular Monolith | MSA | 2 주 내 구현 가능, 운영 비용 ↓, 도메인 경계는 유지하여 추후 분리 여지 |
| 2 | 재고/거래 동시성 제어 | 낙관적 락 + `@Version` + Retry | 비관적 락 | 중고거래 특성상 충돌 빈도 낮음, DB 락 대기 제거로 처리량 ↑ |
| 3 | 다중 서버 WebSocket 메시징 | Redis Pub/Sub | Sticky Session / Kafka | ALB 뒤 다수 인스턴스에서 메시지 일관성 확보, 인프라 오버헤드 최소 |

각 ADR 의 상세 측정 결과는 `docs/adr/` 에 별도 문서화 (Phase 2).

## 기술 스택

- **언어/런타임**: Java 21 (Liberica JDK), Spring Boot 3.5.14
- **데이터**: MySQL 8.0 (JPA/Hibernate), Redis 7 (Lettuce)
- **보안**: Spring Security + JWT (Access/Refresh), BCrypt
- **실시간**: WebSocket (STOMP) + Redis Pub/Sub
- **빌드**: Gradle Groovy DSL
- **배포**: Docker Compose (로컬), AWS EC2 + ALB (운영 — Phase 2)
- **CI**: GitHub Actions
- **부하 테스트**: k6

## 빠른 시작 (로컬)

### 사전 요구 사항

- JDK 21 (`JAVA_HOME` 설정)
- Docker Desktop
- (선택) IntelliJ IDEA

### 1. 환경 변수 파일 생성

```bash
cp .env.example .env
# .env 를 열어 비밀번호를 각자 값으로 변경
```

### 2. 인프라 기동 (MySQL + Redis)

```bash
docker compose up -d
docker compose ps   # 모두 healthy 가 떠야 함
```

| 서비스 | 호스트 포트 | 계정 |
|---|---|---|
| MySQL 8.0 | 3307 → 3306 | `.env` 참조 |
| Redis 7 | 6379 | `.env` 참조 |

> 호스트 MySQL 이 3306 을 이미 쓰고 있을 가능성 때문에 컨테이너는 **3307** 로 노출.

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

### 4. 동작 확인

```bash
curl http://localhost:8080/api/hello
# {"success":true,"code":"OK","data":"Hello, used-trade!", ...}

curl http://localhost:8080/api/hello/health-db
# {"success":true,"code":"OK","data":{"mysql":"UP","redis":"UP"}, ...}
```

## 프로젝트 구조

```
com.portfolio.used_trade
├── UsedTradeApplication.java
├── common/           # cross-cutting: config, exception, response, base entity
│   ├── config/       # SecurityConfig, JpaConfig, ...
│   ├── controller/   # HelloController (초기 검증용)
│   ├── domain/       # BaseEntity (auditing)
│   ├── exception/    # ErrorCode, BusinessException, GlobalExceptionHandler
│   └── response/     # ApiResponse (record)
├── user/             # 회원 (가입/로그인/프로필)
├── product/          # 상품 (등록/검색)
├── trade/            # 거래 (Saga + 낙관적 락)  ← ADR-2
├── chat/             # 채팅 (WebSocket + Redis Pub/Sub)  ← ADR-3
└── payment/          # 결제 (Mock)
```

**의존성 규칙**: 도메인 → common 은 허용, common → 도메인 은 금지 (단방향).

## 개발 로드맵 (2주 압축 플랜)

| 주차 | 목표 |
|---|---|
| W1 Day 1-3 | 환경 세팅 · 공통 구조 · 회원/상품 CRUD · Swagger |
| W1 Day 4-7 | 거래 Saga · 낙관적 락 · Outbox · 결제 Mock · 부하 테스트 |
| W2 Day 1-3 | WebSocket 채팅 · Redis Pub/Sub · 멀티 서버 시연 |
| W2 Day 4-5 | 테스트 커버리지 80% · ADR 문서화 · README 보강 |
| W2 Day 6-7 | AWS 배포 (EC2 + ALB) · CI/CD · 최종 검수 |

## 라이선스

Portfolio project. 외부 사용 금지.
