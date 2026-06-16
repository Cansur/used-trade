# ADR-5 - Saga Orchestration (부분 구현)


### 상태
- **Status**: Accepted (2026-05-09)

## 배경

거래 확정은 결제(외부 PG)와 거래 상태 전환 두 단계를 함께 수행합니다.

외부 PG 호출이 있어서 단일 트랜잭션으로 묶을 수 없습니다.<br>
PG가 결제를 받았는데 우리 DB만 롤백되면 이중장부가 발생합니다.

- 각 단계가 자기 트랜잭션으로 분리될 것
- 중간 실패 시 이미 commit된 단계를 안전하게 되돌릴 것
- 외부 PG 응답 손실 대비 중복 청구 방지 (멱등성)

## 결론

거래 확정을 `Saga Orchestration`으로 부분 구현

- 각 단계가 자기 `@Transactional`, `TradeSagaService`는 트랜잭션 없이 단계 호출
- 결제 실패(T1)만 자동 보상 - T2/크래시/보상 실패는 운영자 수동 조치

## 비교

|  | Saga Orchestration | Saga Choreography |
|---|---|---|
| 흐름 추적·디버깅 | 한 클래스에서 흐름이 보임 | 여러 서비스에 흐름이 흩어짐 |
| 인프라 부담 | 추가 없음 | 이벤트 브로커(Kafka 등) 추가 |
| 모놀리스 적합 | 흐름 owner가 명확 | 낮은 결합의 이득이 작음 |

**Choreography 선택 X**
- 모놀리스에선 Choreography의 장점(낮은 결합)이 작고 인프라 추가 비용 발생
- 거래 흐름의 owner(`trade`)가 명확해 Orchestration이 디버깅·구현 둘 다 유리

## 구현 방법

### 1. Saga 흐름

T0 `validate` → T1 `paymentService.charge()` → 분기:
- PAID   → T2 `tradeService.confirm()`
- FAILED → T1' `tradeService.cancel()`  (보상)

각 단계가 자기 `@Transactional`. `TradeSagaService`는 트랜잭션 없음.

### 2. TradeSagaService
```java
@Service
public class TradeSagaService {
    private final TradeService tradeService;
    private final PaymentService paymentService;

    // @Transactional 없음
    public TradeConfirmResponse confirm(Long buyerId, Long tradeId) {
        Trade trade = loadAndValidate(buyerId, tradeId);
        PaymentResponse payment = paymentService.charge(trade);
        
        if (payment.status() == PAID) {
            return tradeService.confirm(...);
        } else {
            tradeService.cancel(...);  // 보상
            throw new BusinessException(PAYMENT_FAILED);
        }
    }
}
```
- `@Transactional` 없는 이유 : PG 호출 동안 DB 커넥션 점유 + 실패 시 Payment 영속 사라짐
- 각 단계가 자기 트랜잭션이라 commit 즉시 일어남, 보상도 새 트랜잭션

### 3. PaymentService
```java
@Service
public class PaymentService {
    @Transactional
    public PaymentResponse charge(Trade trade) {
        GatewayResult result = gateway.charge(...);
        Payment payment = result.isPaid()
            ? Payment.markPaid(trade, result.gatewayTxId())
            : Payment.markFailed(trade, result.gatewayTxId());
        paymentRepository.save(payment);
        return PaymentResponse.from(payment);  // 예외 안 던지고 정상 반환
    }
}
```
- FAILED여도 예외 안 던짐. 던지면 트랜잭션 롤백되어 FAILED row 사라짐
- gatewayTxId가 멱등성 키

### 측정 결과

`TradeSagaCompensationIT` PG 강제 FAILED 시 - 보상 동작 확인 (Trade·Product 복원, Payment FAILED 영속).

## 에러 상황

### 부분 Saga

자동 보상은 T1(결제 실패)만 구현. 나머지 시나리오는 운영자 수동 조치.

| 시나리오 | 빈도 | 현재 처리 |
|---|---|---|
| T2 실패 (PG PAID 후 `trade.confirm DB` 오류) | 매우 드묾 | log error + 운영자 수동 환불 |
| T1 commit 후 서버 크래시 (T2 진입 전) | 드묾 | 크래시로 인해 log 없음. 운영자 데이터 점검으로 수동 발견 |
| T1' 보상 자체 실패 (trade.cancel DB 오류) | 드묾 | log error + 운영자 수동 |

추후 자동화는 Outbox 패턴으로 통합 처리 예정

## 참조

- [TradeSagaService](../../src/main/java/com/portfolio/used_trade/trade/service/TradeSagaService.java) — Saga 조율자
- [PaymentService](../../src/main/java/com/portfolio/used_trade/payment/service/PaymentService.java) — FAILED도 영속화하는 로직
- [PaymentGatewayPort](../../src/main/java/com/portfolio/used_trade/payment/gateway/PaymentGatewayPort.java) — 외부 PG 추상화
- [TradeSagaCompensationIT](../../src/test/java/com/portfolio/used_trade/trade/service/TradeSagaCompensationIT.java) — 보상 동작 검증 통합 테스트