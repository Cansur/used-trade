# 프로젝트 구조 가이드

> ADR-1 (모듈러 모놀리스) 의 구체적 구현 — 패키지 경계 / 의존 규칙 / 도메인 내부 layout.

---

## 전체 구조

```
com.portfolio.used_trade/
├── UsedTradeApplication.java       엔트리 포인트
├── common/                          cross-cutting (어느 도메인에도 속하지 않는)
│   ├── config/                      SecurityConfig 제외 — JpaConfig, etc
│   ├── controller/                  HelloController (헬스체크 / 데모용)
│   ├── domain/                      BaseEntity (createdAt / updatedAt auditing)
│   ├── exception/                   ErrorCode (enum), BusinessException, GlobalExceptionHandler
│   └── response/                    ApiResponse<T> (모든 API 응답 envelope)
├── user/                            회원 + 인증
│   ├── controller/                  UserController, AuthController
│   ├── service/                     UserService, AuthService, JwtTokenProvider, BlacklistService, RefreshTokenService
│   ├── domain/                      User
│   ├── repository/                  UserRepository
│   ├── dto/                         SignUpRequest, LoginRequest, TokenResponse, UserResponse
│   └── security/                    SecurityConfig, JwtAuthenticationFilter, AuthUser (예외적 위치)
├── product/                         상품
│   ├── controller/                  ProductController
│   ├── service/                     ProductService, ProductImageService
│   ├── domain/                      Product, Category, ProductStatus
│   ├── repository/                  ProductRepository, CategoryRepository
│   ├── dto/                         ProductRegisterRequest, ProductUpdateRequest, ProductResponse, ProductCursorPageResponse
│   ├── bootstrap/                   CategoryDataInitializer (시드 데이터)
│   └── storage/                     ImageStoragePort, MockImageStorage (Hexagonal Port/Adapter)
├── trade/                           거래 (ADR-2 / Saga)
│   ├── controller/                  TradeController
│   ├── service/                     TradeService, TradeSagaService, TradeServiceNaive (ADR-2 Before 시연용)
│   ├── domain/                      Trade, TradeStatus
│   ├── repository/                  TradeRepository
│   ├── dto/                         TradeReserveRequest, TradeResponse, TradeConfirmResponse
│   └── config/                      RetryConfig (@EnableRetry — trade 도메인 종속)
├── chat/                            채팅 (ADR-3)
│   ├── controller/                  ChatRoomController (REST), ChatMessageController (@MessageMapping)
│   ├── service/                     ChatService
│   ├── domain/                      ChatRoom, Message, MessageType
│   ├── repository/                  ChatRoomRepository, MessageRepository
│   ├── dto/                         ChatRoomCreateRequest/Response, MessageSendRequest/Response, MessageCursorPageResponse
│   ├── config/                      WebSocketConfig, JwtChannelInterceptor
│   └── pubsub/                      RedisChatPublisher, RedisChatSubscriber, RedisChatPubSubConfig, RedisChatChannels, ChatBroadcastEvent
└── payment/                         결제 (Mock PG)
    ├── service/                     PaymentService
    ├── domain/                      Payment, PaymentStatus
    ├── repository/                  PaymentRepository
    ├── dto/                         PaymentResponse
    └── gateway/                     PaymentGatewayPort, MockPaymentGateway (외부 PG 추상화)
```

---

## 도메인 내부 layout — 표준 패턴

각 도메인은 다음 5~6 패키지로 일관:

| 패키지 | 책임 | 예 |
|---|---|---|
| `controller` | HTTP / WebSocket 진입점 | `@RestController`, `@MessageMapping` |
| `service` | 비즈니스 로직 + 트랜잭션 경계 | `@Service`, `@Transactional` |
| `domain` | 엔티티 + 도메인 메서드 | `@Entity`, 정적 팩토리, 상태 전이 가드 |
| `repository` | 데이터 접근 | `extends JpaRepository`, QueryDSL (필요 시) |
| `dto` | 입출력 형태 (record) | Request / Response / CursorPage |
| `config` (선택) | 도메인 종속 설정 | `RetryConfig`, `WebSocketConfig` |

선택 패키지:
- `bootstrap` — 시드 데이터 (예: `CategoryDataInitializer`)
- `storage` / `gateway` — 외부 시스템 어댑터 (Hexagonal Port/Adapter)
- `pubsub` — 이벤트 발행 / 구독 인프라
- `security` — 인증 / 인가 관련 (user 도메인 한정)

---

## 의존 규칙

### 규칙 1: 의존 방향 — 도메인 → common 만

```
[user]  [product]  [trade]  [chat]  [payment]
   │       │         │        │        │
   └───────┴─────────┴────────┴────────┘
                     │
                     ▼
                 [common]
```

- ✅ `trade.service` 가 `common.exception.BusinessException` 사용
- ✅ `chat.dto` 가 `common.response.ApiResponse` 사용
- ❌ `common` 패키지 안에서 `User`, `Trade` 등 도메인 엔티티 import

**이유**: common 이 도메인에 의존하면 cross-cutting 모듈이 아니라 또 다른 도메인이 됨. 그러면 어떤 도메인을 분리할 때 common 도 같이 끌려다님.

### 규칙 2: 도메인 간 — ID (값) 만 참조, 엔티티 직접 참조 금지

```java
// ✅ OK — ID 만
public class ChatRoom {
    private Long buyerId;   // User 의 ID (값)
    private Long sellerId;
    private Long productId; // Product 의 ID (값)
}

// ❌ 안 됨 — 엔티티 직접
public class ChatRoom {
    @ManyToOne
    private User buyer;     // Cross-domain 엔티티 참조
}
```

**이유**:
- Cross-domain 엔티티 참조하면 JPA fetch 전략 / 연관 관계 매핑이 그 도메인의 변경에 영향 받음 → 분리 시 의존성 헝클어짐
- ID 만 들고 있으면 추후 다른 도메인을 다른 DB 로 분리하거나 MSA 로 이전할 때 그대로 가능

### 규칙 3: Cross-domain 흐름은 별도 Service

여러 도메인을 묶는 비즈니스 흐름 (예: 거래 확정 = 결제 + 거래 상태 전환) 은 한 도메인 안에 둠 — 그 흐름의 비즈니스 owner.

예 — `TradeSagaService`:
- `trade.service` 패키지에 둠 (거래가 비즈니스 owner)
- `paymentService.charge()` 와 `tradeService.confirm()` 을 조율
- payment 도메인은 SDK 처럼 호출만 됨 — 자기 트랜잭션만 책임

```java
// trade/service/TradeSagaService.java
public class TradeSagaService {
    private final TradeRepository tradeRepository;
    private final TradeService tradeService;       // 같은 도메인
    private final PaymentService paymentService;   // 다른 도메인 (Cross-domain 의존 — Saga 의 특권)

    public TradeConfirmResponse confirm(Long buyerId, Long tradeId) {
        // ... T0/T1/T2/T1' 흐름
    }
}
```

→ "Saga 클래스" 자체가 cross-domain 의존을 명시적으로 격리하는 역할. 다른 일반 Service 는 자기 도메인 안에서만 동작.

---

## 예외 케이스 — SecurityConfig 가 user 안에 있는 이유

```
user/security/
├── SecurityConfig.java           ← 일반적으론 common/config 에 있을 법
├── JwtAuthenticationFilter.java
├── JwtTokenProvider.java
└── AuthUser.java
```

문제:
- `SecurityConfig` 가 `JwtAuthenticationFilter` 를 주입받음
- `JwtAuthenticationFilter` 는 user 도메인 종속 (RefreshTokenService / BlacklistService / User 조회)

만약 `SecurityConfig` 가 `common` 에 있으면:
- `common` → `user` 의존 발생 (규칙 1 위반)

해결:
- SecurityConfig 자체를 `user.security` 로 이동 → 의존 방향 유지

다른 도메인이 인증 빈 (`PasswordEncoder`, JWT 검증) 을 필요로 하면:
- user 가 `@Bean` 으로 노출
- 다른 도메인이 주입 받음 (도메인 → user 의존)

**원칙**: cross-cutting 처럼 보여도 도메인 종속성 있으면 그 도메인 안에 둠.

---

## 예외 케이스 — `TradeServiceNaive` 의 존재

`trade.service.TradeServiceNaive` 는 production 에서 안 쓰이는 클래스. 존재 이유:

- **ADR-2 (낙관적 락) 의 Before 측 시연** — 보호 없이 reserve 했을 때 중복 거래 발생 입증
- `TradeReserveNaiveTest` 통합 테스트가 사용 — production 도메인 모듈에 두는 게 부담스러우면 `src/test/java/` 의 fixture 로 옮길 수 있음
- 현재는 production 코드 옆에 두어 **시연 가치** 극대화 — 면접 때 "여기 보세요, 같은 도메인에 보호 있는 버전과 없는 버전 둘 다 있고 통합 테스트가 둘 다 호출합니다"

→ 코드 위치는 트레이드오프. **목적이 production 이 아님을 클래스명 (Naive) 에 명시**.

---

## DTO — record + 정적 팩토리

모든 DTO 는 `record`:

```java
public record TradeResponse(
        Long id,
        Long productId,
        Long buyerId,
        Long sellerId,
        Long pricePaid,
        TradeStatus status,
        LocalDateTime createdAt
) {
    public static TradeResponse from(Trade trade) {
        return new TradeResponse(
                trade.getId(),
                trade.getProductId(),
                trade.getBuyerId(),
                trade.getSellerId(),
                trade.getPricePaid(),
                trade.getStatus(),
                trade.getCreatedAt()
        );
    }
}
```

원칙:
- **`record` — Java 16+ 의 immutable 데이터** — getter / equals / hashCode / toString 자동
- **`from(Entity)` 정적 팩토리** — 매핑 책임을 DTO 가 가짐 (Service 에서 매핑 코드 없음)
- **변환은 단방향** — DTO → Entity 변환은 도메인 정적 팩토리 (예: `Trade.reserve(buyer, product)`) 가 책임
- **Bean Validation** 어노테이션은 Request DTO 에만 (Response 에는 의미 없음)

---

## ApiResponse — 모든 응답의 envelope

```java
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        LocalDateTime timestamp
) {
    public static <T> ApiResponse<T> success(T data) { ... }
    public static <T> ApiResponse<T> error(ErrorCode code) { ... }
}
```

원칙:
- **모든 컨트롤러가 `ApiResponse<T>` 반환** — 일관성
- 성공: `{success: true, code: "OK", data: {...}}`
- 실패: `{success: false, code: "TRADE_ALREADY_RESERVED", message: "..."}`
- 클라이언트는 `success` 만 체크해 분기 가능

`GlobalExceptionHandler` 가 `BusinessException` 을 잡아 자동으로 `ApiResponse.error()` 로 변환 → 컨트롤러 코드엔 try-catch 없음.

---

## 면접 대비

### Q. "도메인 경계 어떻게 enforce 했나요?"

**A**: "세 가지 규칙으로:
1. 의존 방향 단방향 — 도메인 → common, common ↛ 도메인. `common` import 만 봐도 위반 즉시 발견.
2. 도메인 간 엔티티 직접 참조 금지 — ID (값) 만 들고 있음. `ChatRoom.buyerId: Long` 같은 식. JPA `@ManyToOne` 으로 다른 도메인 entity 매핑 X.
3. Cross-domain 흐름은 Saga 클래스 같은 별도 Service 에 격리 — 그 흐름의 비즈니스 owner 도메인 안에 둠.

컴파일 시점 enforce (ArchUnit) 는 안 했어요 — 본 프로젝트 규모에선 PR 코드 리뷰가 더 효과적이라 판단. 도메인이 늘어나거나 팀이 커지면 ArchUnit 도입 고려."

### Q. "왜 SecurityConfig 가 common 이 아니라 user 안에 있어요?"

**A**: "`SecurityConfig` 가 `JwtAuthenticationFilter` 를 주입받고, 그 필터는 user 도메인 (RefreshTokenService / BlacklistService) 에 의존하기 때문입니다. SecurityConfig 를 common 에 두면 common → user 의존 발생해 규칙 1 위반. **cross-cutting 처럼 보여도 도메인 종속성 있으면 그 도메인 안에 두는 게 일관 원칙**. 다른 도메인이 인증 빈을 필요로 하면 user 가 `@Bean` 으로 노출하고 다른 도메인이 주입받는 방향 (도메인 → user 의존 — 규칙 1 통과)."

### Q. "DTO 와 Entity 의 매핑은 어떻게 했나요?"

**A**: "**DTO 가 매핑 책임** — `record` 의 `from(Entity)` 정적 팩토리. Service 코드에 매핑 코드 없음. 예:
```java
public record TradeResponse(Long id, ..., TradeStatus status) {
    public static TradeResponse from(Trade trade) { ... }
}
// Service 에서:
return TradeResponse.from(trade);
```

장점: Service 가 비즈니스 로직만, Controller 가 DTO 변환 만, Entity 가 도메인 메서드만 — 책임 분리. 단점: DTO 가 Entity 의 getter 에 의존 — 도메인 객체 변경 시 DTO 도 같이 수정. 본 프로젝트 규모에선 MapStruct 같은 도구는 과잉."

---

## References

- 모듈러 모놀리스 결정 근거: [ADR-1](../adr/001-modular-monolith.md)
- Saga cross-domain 흐름: [ADR-5](../adr/005-saga-orchestration.md)
- Hexagonal Architecture Port/Adapter: `product/storage/ImageStoragePort.java`, `payment/gateway/PaymentGatewayPort.java`
