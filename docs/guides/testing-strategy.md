# 테스트 전략 가이드

> 138 테스트의 분류 / 도구 / 의사결정. 면접 답변: "테스트 어떻게 짜셨어요?"

---

## 한눈에

| 지표 | 값 |
|---|---|
| 총 테스트 메서드 | **138** (`./gradlew test` 기준) |
| 실패 | **0** |
| 무시 (ignored) | 0 |
| 실행 시간 | 40초 |
| 통합 테스트 (`@SpringBootTest`) | 7개 파일 |
| 벤치마크 (`@Tag("benchmark")`) | 1개, 별도 task |

도메인별 분포:

| 도메인 | 메서드 수 |
|---|---|
| user | 38 |
| product | 37 |
| trade | 29 |
| chat | 23 |
| payment | 11 |

---

## 도구 스택

| 도구 | 용도 |
|---|---|
| **JUnit 5** | 테스트 프레임워크 (`@Test`, `@ParameterizedTest`, `@Nested`, `@DisplayName`, `@Tag`) |
| **AssertJ** | Fluent assertion (`assertThat(...).isEqualTo(...)`) |
| **Mockito** | 단위 테스트의 의존성 mocking |
| **Spring Boot Test** | 통합 테스트 (`@SpringBootTest`, `@MockBean`) |
| **WebSocketStompClient** | STOMP 통합 테스트 (chat 도메인) |
| **SpringApplicationBuilder** | 같은 테스트 안에서 두 번째 JVM 인스턴스 시동 (cross-instance 시연) |
| **JdbcTemplate** | 벤치마크의 raw SQL 측정 (JPA 오버헤드 회피) |

**의도적으로 안 쓴 것**:
- **TestContainers**: 로컬 / CI 에서 `docker-compose` 가 항상 MySQL + Redis 띄워두는 워크플로우라 TestContainers 의 가치가 작음. CI 가 docker compose up 으로 인프라 띄움. → 테스트 cycle ~40초 (TestContainers 면 컨테이너 시동 시간 추가)
- **AssertJ 의 db assertion library (AssertDbConnection)**: 통합 테스트에서 Repository 의 직접 쿼리로 충분
- **WireMock**: 외부 서비스 mock — 현재는 PG 만 외부, 그것도 `MockPaymentGateway` 빈으로 처리

---

## 분류 — 단위 vs 통합

### 단위 테스트 (131개)

**도메인 단위** — 엔티티의 도메인 메서드 / 상태 전이 가드 / 정적 팩토리:

```java
// trade/domain/TradeTest.java 예시
@Test
@DisplayName("RESERVED 상태에서 confirm() 호출 시 CONFIRMED 로 전이")
void confirm_fromReserved_changesStatusToConfirmed() {
    Trade trade = Trade.reserve(buyer, product);
    trade.confirm();
    assertThat(trade.getStatus()).isEqualTo(TradeStatus.CONFIRMED);
}

@Test
@DisplayName("CONFIRMED 상태에서 cancel() 호출 시 INVALID_TRADE_TRANSITION")
void cancel_fromConfirmed_throwsBusinessException() {
    Trade trade = Trade.reserve(buyer, product);
    trade.confirm();
    assertThatThrownBy(trade::cancel)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TRADE_TRANSITION);
}
```

**서비스 단위** — Mockito 로 의존성 mock + 분기 / 가드 검증:

```java
// trade/service/TradeServiceTest.java 예시
@ExtendWith(MockitoExtension.class)
class TradeServiceTest {
    @Mock private TradeRepository tradeRepository;
    @Mock private ProductRepository productRepository;
    @InjectMocks private TradeService tradeService;

    @Test
    @DisplayName("reserve — 자기 상품 예약 시 TRADE_SELF_NOT_ALLOWED")
    void reserve_selfTrade_throws() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(productOf(sellerId=1L)));
        assertThatThrownBy(() -> tradeService.reserve(buyerId=1L, productId=1L))
                .extracting("errorCode").isEqualTo(ErrorCode.TRADE_SELF_NOT_ALLOWED);
    }
}
```

### 통합 테스트 (7개)

| 파일 | 종류 | 책임 |
|---|---|---|
| `TradeReserveNaiveTest` | `@SpringBootTest` + 동시 호출 | **ADR-2 Before** — 보호 없는 reserve 의 중복 거래 결함 입증 |
| `TradeReserveConcurrencyTest` | 같음 | **ADR-2 After (N=20)** — 1건 RESERVED, 중복 0 |
| `TradeReserveLoadTest` | 같음 | **ADR-2 정량 (N=50)** — wall=246ms, p95=193ms |
| `TradeSagaCompensationIT` | `@SpringBootTest` + `ControllablePaymentGateway` | **Saga 보상** — PG FAILED → Product 상태 원복 |
| `ChatStompSingleInstanceTest` | `@SpringBootTest(RANDOM_PORT)` + STOMP client | STOMP 핸드셰이크 / JWT CONNECT / SUBSCRIBE 가드 |
| `ChatStompDualInstanceTest` | 같음 + `SpringApplicationBuilder` 두번째 JVM | **ADR-3 핵심** — cross-instance broadcast |
| `CursorPagingBenchmarkTest` | `@SpringBootTest` + `@Tag("benchmark")` | **ADR-4 벤치** — OFFSET vs CURSOR p50 측정 + EXPLAIN |

---

## 핵심 의사결정

### 1. 왜 `@Tag("benchmark")` 로 벤치마크 분리?

```gradle
// build.gradle
tasks.named('test') {
    useJUnitPlatform { excludeTags 'benchmark' }
}
tasks.register('benchmark', Test) {
    useJUnitPlatform { includeTags 'benchmark' }
    testLogging { showStandardStreams = true }
}
```

**이유**:
- `CursorPagingBenchmarkTest` 는 **10만 건 시드 + 워밍업 5회 + 100회 반복** → 시간 ~30초~수분
- 매 PR 마다 CI 에서 돌면 빌드 시간이 부담 + flaky 위험 (반복 측정의 분산)
- 일반 `./gradlew test` 에선 제외, **별도 `./gradlew benchmark` 로만 실행** — 수동 / 측정 필요할 때

### 2. 왜 TestContainers 안 쓰고 docker-compose 의존?

**이유**:
- 로컬 개발 워크플로우가 이미 `docker compose up -d` 로 MySQL/Redis 항상 띄워둠
- CI (GitHub Actions) 가 services: 로 mysql / redis 띄워주는 게 더 빠름 (TestContainers 는 컨테이너 시동 시간 추가)
- TestContainers 의 가치 (테스트 격리 / 환경 재현 가능성) 는 **외부 의존성 자주 바뀔 때** 큼. 본 프로젝트는 MySQL 8 / Redis 7 고정 → 가치 작음

**언제 TestContainers 도입할까**:
- CI 환경이 docker compose 못 띄울 때
- 여러 DB 버전 동시 테스트 필요
- 분기별 외부 의존 (LocalStack 의 S3 / SQS / Kafka 등) 추가될 때

### 3. 왜 `TradeReserveNaiveTest` 를 굳이 남겨두나

`TradeServiceNaive` 는 production 에서 안 쓰임. 그래도 통합 테스트로 남긴 이유:

- **ADR-2 의 Before 측 회귀 가드** — "보호 없으면 중복 거래 3건 발생" 을 코드로 입증
- 누군가 미래에 "낙관적 락 어차피 안 필요하지 않나?" 라고 제거하려 들면 이 테스트가 막아줌
- 면접 시연 시 "Before/After 어떻게 입증했나" → 두 테스트 (Naive / Concurrency) 가 같이 돌면서 결함 → 수정 흐름 보여줌

### 4. 왜 `ChatStompDualInstanceTest` 에 SpringApplicationBuilder 쓰나

```java
SpringApplicationBuilder builderB = new SpringApplicationBuilder(UsedTradeApplication.class)
        .properties("server.port=" + portB)
        .properties("spring.data.redis.host=" + redisHost) // 같은 Redis 공유
        .web(WebApplicationType.SERVLET);
ConfigurableApplicationContext contextB = builderB.run();
```

**이유**:
- ADR-3 의 핵심 가치 = "다른 JVM 인스턴스에 붙은 사용자에게도 메시지 도달"
- 단일 SpringBootTest 안에서 두 JVM 시뮬레이션 필요
- `SpringApplicationBuilder` 로 두번째 ApplicationContext 띄워 다른 포트 / 같은 Redis 사용
- 둘 다 같은 `chat.broadcast` 채널 구독 → 한쪽이 publish → 다른쪽이 SUBSCRIBE 한 클라이언트도 받음

**대안**:
- 두 컨테이너 띄워 외부 통신 → 환경 의존성 늘어남 (CI 에서 어렵)
- Mock 두 개 만들기 → 실제 Redis Pub/Sub 동작 검증 못 함 (의미 없음)

→ `SpringApplicationBuilder` 가 **테스트 안에서 분산 환경 시뮬레이션** 의 가장 가벼운 답.

### 5. 왜 `@DisplayName` 한글 / 자연어로

```java
@Test
@DisplayName("RESERVED 상태에서 confirm() 호출 시 CONFIRMED 로 전이")
void confirm_fromReserved_changesStatusToConfirmed() { ... }
```

**이유**:
- 메서드명은 Java 컨벤션 (`methodName_scenario_expectedBehavior`)
- `@DisplayName` 은 한글 자연어 — 테스트 리포트 (build/reports/tests/test/index.html) 에서 비개발자도 의도 파악
- 면접에서 "테스트 어떤 시나리오 다뤘나" 질문 시 리포트 한 번에 보여줌

---

## 커버리지 관점

JaCoCo 같은 line coverage 도구는 **안 적용**. 이유:

- **Coverage % 목표** 는 안티패턴 — 의미 없는 getter 테스트로 숫자 끌어올리는 게 가능
- 본 프로젝트는 **시나리오 커버리지 우선** — 각 ADR 의 Before/After + 도메인 메서드의 가드 / 분기 / 상태 전이가 다 테스트됨
- 138 테스트 모두 의미 있는 시나리오 (의도된 결함 입증 / 보호 입증 / 분기 / 가드)

**언제 JaCoCo 도입할까**:
- 팀 협업 시작 → 새 코드의 커버리지 강제
- 운영 환경에서 회귀 사고 발생 → 커버리지 hole 추적

---

## 빠른 실행

```bash
# 1. 인프라 띄우기
docker compose up -d
docker compose ps   # mysql / redis 모두 healthy

# 2. 전체 단위 + 통합
./gradlew test

# 3. 벤치마크만
./gradlew benchmark

# 4. 특정 테스트 파일
./gradlew test --tests "com.portfolio.used_trade.trade.service.TradeReserveLoadTest"

# 5. 캐시 무시 강제 재실행
./gradlew test --rerun-tasks

# 6. 리포트
# build/reports/tests/test/index.html
```

---

## 면접 대비

### Q. "테스트 어떻게 구성하셨어요?"

**A**: "138 테스트 (단위 + 통합) 모두 통과. 도메인별로 단위 테스트 (Mockito) + 통합 테스트 (`@SpringBootTest`) 가르고, 통합은 7개로 ADR 의 가치 입증에 집중:
- ADR-2 동시성 — Naive (Before) / Concurrency (After) / Load (정량)
- Saga 보상 — `TradeSagaCompensationIT`
- ADR-3 — STOMP 단일 / 다중 인스턴스 (`SpringApplicationBuilder` 로 두 JVM)
- ADR-4 — 커서 페이징 벤치 (`@Tag("benchmark")` 로 별도 task)

벤치마크는 `./gradlew test` 에서 제외, `./gradlew benchmark` 로만 — CI 부담 회피. TestContainers 안 썼고 docker compose 의존 — 본 프로젝트 환경에서 cycle 더 빠름."

### Q. "왜 TestContainers 안 썼나요?"

**A**: "로컬 / CI 둘 다 `docker compose up -d` 로 MySQL / Redis 띄워두는 워크플로우라 TestContainers 의 가치 (테스트 격리 / 컨테이너 자동 관리) 가 작았어요. 컨테이너 시동 시간 (~10초/테스트) 도 추가 비용. 환경이 LocalStack (S3/SQS) 같이 다양한 외부 의존성 합류하면 TestContainers 도입 고려."

### Q. "테스트 커버리지 % 는 얼마예요?"

**A**: "JaCoCo 등 line coverage 도구 안 썼습니다. **Coverage % 가 안티패턴 가능성** 때문 — getter 테스트로 숫자 부풀리기 가능. 본 프로젝트는 **시나리오 커버리지** 우선 — 각 ADR 의 Before/After, 도메인 메서드의 가드 / 분기 / 상태 전이가 다 테스트됨. 138 테스트 모두 의미 있는 시나리오. 팀 협업 시작하면 새 코드 강제용으로 JaCoCo 도입 검토."

### Q. "Saga 보상 어떻게 테스트했나요?"

**A**: "`TradeSagaCompensationIT` 가 핵심:
1. `ControllablePaymentGateway` (테스트 전용 빈, `@Profile("test")`) 를 만들어 PG 응답 외부에서 조작
2. Bob 이 거래 예약 (RESERVED)
3. PG 를 강제 FAILED 로 설정 후 confirm 호출
4. 검증: trade.status = CANCELED, product.status = AVAILABLE (복원), payment row = FAILED (영속됨), 응답 = 402 PAYMENT_FAILED

production 의 `MockPaymentGateway` 는 항상 성공 — happy path 시연 / 일반 통합 테스트용. PG 실패 시나리오만 `ControllablePaymentGateway` 로 시연."

---

## References

- 각 ADR 의 회귀 가드 섹션 — [ADR-2](../adr/002-optimistic-locking.md), [ADR-3](../adr/003-redis-pubsub-chat.md), [ADR-4](../adr/004-cursor-pagination.md), [ADR-5](../adr/005-saga-orchestration.md)
- 테스트 코드 — `src/test/java/com/portfolio/used_trade/`
- 빌드 설정 — [build.gradle](../../build.gradle) (test / benchmark task)
