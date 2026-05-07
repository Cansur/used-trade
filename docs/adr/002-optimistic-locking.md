# ADR-2 — 거래 동시성 제어: 낙관적 락 + Spring Retry

- **Status**: Accepted (2026-05-07)
- **Context**: Phase 2 / W2 Day 1 — Trade 도메인 reserve 1차 구현
- **Related**: ADR-1 (모듈러 모놀리스), ADR-4 (커서 페이징)

---

## 결정

거래 예약(`Trade.reserve`) 의 동시성 충돌 제어로 **낙관적 락 + Spring Retry** 채택.

- `Product.@Version` 으로 동일 row 동시 갱신 검출
- `TradeService.reserve` 에 `@Retryable + saveAndFlush` 로 충돌이 메서드 안에서 터지도록 강제
- `OptimisticLockingFailureException` + `CannotAcquireLockException` (MySQL InnoDB 데드락) 을 재시도 대상으로 묶음
- 재시도 정책: max=3, 지수 백오프 (50ms × multiplier 2)
- 재시도 모두 실패 시 `@Recover(DataAccessException)` 가 `TRADE_ALREADY_RESERVED` 로 변환

---

## Context — 왜 이 결정이 필요한가

중고거래 도메인에서 같은 상품에 대한 **동시 예약 요청**이 들어왔을 때, 정확히 한 명만 거래를 시작할 수 있어야 한다. 보호가 없으면 다음 결함이 발생한다 (실측, 본 ADR 의 Before 측):

```
[naive-reserve N=20] attempts=20, succeeded=3, duplicate-trade-rows=3
```

같은 product 에 대해 trades 테이블에 row 가 3건 생성됐다. 즉 상품 1개를 3명에게 동시 판매한 셈.

근본 원인: `SELECT product → status check → UPDATE → INSERT trade` 의 비원자성. 두 트랜잭션이 같은 snapshot 위에서 `status='AVAILABLE'` 을 보고 각자 UPDATE 한다. WHERE 절에 status 조건이 없으면 lost update, INSERT 에 중복 검사가 없으면 trades 가 늘어난다.

---

## 옵션 비교

| 축 | 낙관적 락 + Retry (선택) | 비관적 락 (`SELECT … FOR UPDATE`) | Redis 분산락 (Redisson) |
|---|---|---|---|
| 충돌 빈도 적은 워크로드 | **유리** — 락 대기 없음 | 항상 락 점유로 처리량 저하 | 무관 |
| 충돌 빈도 높은 워크로드 | retry 비용 누적 | 직렬화로 안정 | 직렬화로 안정 |
| 인프라 복잡도 | DB 만 (현재 스택) | DB 만 | Redis 추가 + 장애 시 정합성 리스크 |
| 데드락 위험 | 낮음 (retry 흡수) | 높음 (lock 순서 의존) | 낮음 |
| 학습/디버깅 비용 | JPA `@Version` 표준 | DB 락 동작 이해 필요 | TTL/페일오버 설계 추가 |
| 본 프로젝트 적합도 | **○** — 동일 상품에 동시 충돌 빈도가 사실상 낮음 | 처리량 손해 | 인프라 복잡도 대비 이득 미미 |

**기각 이유:**
- **비관적 락**: 중고거래는 동일 상품에 동시 충돌이 드물다. 항상 락을 잡는 비용이 평균적으로 손해. 또한 트랜잭션이 길어질 경우 데드락 위험이 커진다.
- **Redisson**: Redis 가 본 프로젝트에서 세션/캐시/Pub/Sub 으로 이미 쓰이지만, 분산락 도입 시 Redis 장애가 거래 정합성 리스크로 직접 연결된다 (락 임차 만료, split-brain). DB 단독으로 충분한 보호를 얻을 수 있다면 그게 단순.

---

## Before / After 정량 측정

동일 셋업 — `@SpringBootTest`, `ExecutorService` 동시 호출, 같은 product 대상.
HikariCP `maximum-pool-size=10` 인 로컬 환경.

| 항목 | Before (보호 없음, N=20) | After (보호 있음, N=20) | After (보호 있음, N=50) |
|---|---|---|---|
| 성공 (Trade 생성) | 3 건 | **1 건** | **1 건** |
| trades row 수 | 3 (중복 거래) | **1** | **1** |
| 정확성 | **결함 발생** | **무결성 유지** | **무결성 유지** |
| wall-clock total | 측정 안 함 | (측정 안 함) | 246 ms |
| per-call avg | — | — | 153 ms |
| per-call p50 | — | — | 158 ms |
| per-call p95 | — | — | **193 ms** |
| per-call max | — | — | 242 ms |
| 예상 외 예외 | — | 0 | 0 |

**핵심:**
- Before 의 결함은 단순한 락 경합이 아니라 **데이터 손상**. 같은 상품을 여러 명에게 동시 판매한 흔적이 trades 에 남는다.
- After 는 동일 시나리오를 N=50 까지 키워도 **trades row 정확히 1건**, 나머지 49 건은 `PRODUCT_NOT_AVAILABLE` 로 거부.
- p95 193ms 는 connection pool=10 인 환경의 직렬화 비용. pool 을 키우거나 트랜잭션 시간을 줄이면 더 짧아질 여지.

---

## 구현 노트

### 왜 `saveAndFlush` 인가
`save` 만 쓰면 flush 가 트랜잭션 커밋 시점에 일어난다. `OptimisticLockingFailureException` 이 메서드 <i>밖</i>에서 발생하고 `@Retryable` 이 잡지 못한다. `saveAndFlush` 는 메서드 안에서 즉시 flush 를 강제 → 충돌이 메서드 안에서 터짐 → 재시도 가능.

### 왜 `CannotAcquireLockException` 도 재시도 대상인가
처음 구현은 `OptimisticLockingFailureException` 만 retry 대상으로 두었다. 통합 테스트 (N=20) 에서 일부 호출이 `ExhaustedRetryException("Cannot locate recovery method") <- MySQLTransactionRollbackException("Deadlock found")` 로 떨어졌다.

InnoDB 가 동시 row 갱신 경합을 데드락으로 검출해 트랜잭션을 롤백할 때 `MySQLTransactionRollbackException` 을 던지고, Spring 이 이를 `CannotAcquireLockException` 으로 변환한다. 이 또한 동시성 충돌이므로 retry 대상에 포함하는 게 자연스럽다. `@Recover` 시그니처를 두 예외의 공통 슈퍼타입인 `DataAccessException` 으로 통합해 한 메서드로 처리.

### 왜 `BusinessException` 용 `@Recover` 가 별도로 필요했나
`@Retryable.retryFor` 에 매칭되지 않는 예외 (`BusinessException` 등) 가 발생하면 Spring Retry 는 즉시 종료하고 `@Recover` 를 탐색한다. 매칭되는 시그니처가 없으면 원본 예외를 그대로 던지지 않고 `ExhaustedRetryException("Cannot locate recovery method")` 으로 감싼다. 즉 `PRODUCT_NOT_FOUND`, `TRADE_SELF_NOT_ALLOWED`, `PRODUCT_NOT_AVAILABLE` 가 의도한 4xx 가 아닌 500 으로 묻힌다.

해법: `@Recover(BusinessException) → throw ex` 로 그대로 rethrow. 이 회귀는 curl 스모크에서 발견했고 동시성 통합 테스트가 박았다.

---

## Consequences

**얻은 것:**
- 동일 상품 동시 예약 시 데이터 무결성 (trades row 정확히 1건)
- DB 단독으로 보호 — 추가 인프라 의존성 0
- 충돌이 드문 일반 상황에선 락 대기 비용 0

**대가:**
- retry 가 발생하면 응답 시간이 늘어난다 (50ms × 시도 횟수의 backoff). 동시 부하가 높을수록 누적.
- HikariCP connection pool 부담. N 이 pool size 보다 크면 connection 대기가 측정값을 지배 — 본 ADR 의 N=50 측정도 이 영향을 일부 받는다.
- `@Retryable` 의 self-invocation 함정. 같은 클래스 내부 호출은 프록시를 거치지 않아 retry/트랜잭션이 적용되지 않는다 — 컨트롤러에서만 진입하도록 강제.

**언제 재검토하나:**
- 같은 상품에 대한 동시 reserve 비율이 의미 있게 올라가면 (예: 한정판 드롭 시나리오) Redisson 분산락이나 큐 직렬화를 검토.
- p95 가 SLO 를 깨면 트랜잭션 범위 축소, pool 크기 조정, native conditional update (`UPDATE products SET status='TRADING' WHERE id=? AND status='AVAILABLE'`) 로의 부분 이행을 검토.

---

## 검증 — 본 ADR 의 회귀 가드

| 테스트 | 책임 |
|---|---|
| `TradeTest` (단위 14건) | 도메인 메서드 가드, 가격 스냅샷, 상태 전이 회귀 |
| `TradeServiceTest` (단위 5건) | 존재 검증, 도메인 가드 위임 |
| `TradeReserveConcurrencyTest` (통합 N=20) | After 정확성 — 1건 RESERVED, 중복 0, 예상 외 예외 0 |
| `TradeReserveNaiveTest` (통합 N=20) | Before 결함 입증 — 중복 거래 ≥ 2건 |
| `TradeReserveLoadTest` (통합 N=50) | After 정량 — 응답 시간 분포 + 정확성 회귀 |

---

## References

- 본문 측정값은 commit 시점 로컬 환경 (Docker MySQL 8.0, HikariCP max=10) 기준
- Spring Retry 문서: `org.springframework.retry.annotation.Retryable`, `Recover`
- JPA `@Version` 동작: Hibernate User Guide §5.2 Optimistic Locking
