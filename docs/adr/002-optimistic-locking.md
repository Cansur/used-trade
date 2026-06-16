# ADR-2 - 낙관적 락

### 상태

- **Status**: Accepted (2026-05-07)

<br>

## 배경

거래 예약(`reserve`)기능에서 같은 상품을 동시에 예약하면 거래가 중복 생성이 될 수 있습니다.<br>
상태를 조회한 시점과 변경하는 시점 사이에 다른 트랜잭션이 같은 상태를 보고 둘 다 통과해 버리기 때문입니다.<br>
중고거래 특성상 동시 요청 빈도는 낮지만, 한 번이라도 발생하면 데이터 무결성이 깨집니다

- 동일 상품 동시 요청 시 정확히 한 건만 통과
- 충돌 빈도가 낮은 평상시엔 성능 오버헤드가 작을 것
- 단일 DB 환경에서 동작 - 추가 인프라 의존 최소화

## 결론

거래 예약(`reserve`)의 동시성 제어로 낙관적 락 + Spring Retry.

- `Product`에 `@Version` 으로 동일 row 동시 갱신 검출
- `TradeService.reserve` 에 `@Retryable` + `saveAndFlush` 로 충돌을 메서드 내부에서 검출 후 재시도

## 비교

|  | 낙관적 락 + Retry | 비관적 락 | Redis 분산락 |
|---|---|---|---|
|저빈도 충돌 환경|유리 (락 비용 0)|손해 (항상 락 점유)	|무관|
|고빈도 충돌 환경|retry 비용 누적|직렬화로 안정|직렬화로 안정|
|인프라 부담|DB만|DB만|Redis 추가 + 장애 시 정합성 리스크|
<br>

비관적 락 선택 X
- 중고거래는 동일 상품 동시 충돌이 드물어 항상 락을 잡는 비용이 평균적으로 손해

Redis 분산락 선택 X
- Redis 장애가 거래 정합성 리스크로 직결 (락 임차 만료·split-brain)
- DB 단독으로 충분한 보호 가능한데 추가 인프라 의존이 부담

<br>

## 구현 방법

### 1. Product 엔티티에 @Version
```java
@Entity
public class Product {
    @Version
    private Long version;
}
```
- UPDATE 시 `WHERE version=?` 조건이 자동 추가

### 2. @Retryable + saveAndFlush
```java
@Retryable(
    retryFor = OptimisticLockingFailureException.class,
    maxAttempts = 3,
    backoff = @Backoff(delay = 50, multiplier = 2)
)

@Transactional
public TradeResponse reserve(Long buyerId, Long productId) {
    // ... 도메인 로직
    Trade saved = tradeRepository.saveAndFlush(trade);  // 즉시 flush
    return TradeResponse.from(saved);
}
```

- saveAndFlush - 충돌을 메서드 안에서 발생시켜 @Retryable이 잡게 함
- 재시도 정책 - 최대 3회, 50ms × 2

### 측정 결과

| 시나리오 | 결과 |
|---|---|
| N=50 동시 reserve | Trade row 1건 / 거부 49건 |
| p95 latency | 193ms |

<br>

## 에러 상황

### 1. CannotAcquireLockException 재시도 에러 추가

상황 : 처음엔 `OptimisticLockingFailureException`만 재시도 에러로 적용

문제 : 하지만 데드락 검출(DB 엔진 레벨)로 `CannotAcquiteLockException`가 발생하여 `500`에러 발생

결론 : 그래서 `retryFor`에 `CannotAcquiteLockException` 추가

### 2. @Recover 별도 필요

상황 : 존재하지 않는 상품을 reserve요청으로 `404`에러가 나올 것을 예상

문제 : 하지만 실제로는 `500 Internal_Server_Error`가 발생

결론 : Spring Retry가 그대로 통과 시키도록 `@Recover`를 사용

<br>

## 참조

- [Product](../../src/main/java/com/portfolio/used_trade/product/domain/Product.java) — `@Version` 필드 위치
- [TradeService](../../src/main/java/com/portfolio/used_trade/trade/service/TradeService.java) — `@Retryable` + `saveAndFlush` 구현
- [TradeReserveConcurrencyTest](../../src/test/java/com/portfolio/used_trade/trade/service/TradeReserveConcurrencyTest.java) — N=20 정확성 회귀 가드
- [TradeReserveLoadTest](../../src/test/java/com/portfolio/used_trade/trade/service/TradeReserveLoadTest.java) — N=50 정량 측정 (p95)