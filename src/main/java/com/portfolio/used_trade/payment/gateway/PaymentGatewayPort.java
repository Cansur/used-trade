package com.portfolio.used_trade.payment.gateway;

/**
 * 외부 결제 게이트웨이 추상화 (Hexagonal Port).
 *
 * <p>도메인 코드는 본 인터페이스에만 의존 — 어댑터 (Mock / 진짜 PG) 는 교체 가능.
 * 통합 테스트는 {@code ControllablePaymentGateway} 같은 테스트용 구현으로 대체.
 *
 * <p>{@link Result} 는 sealed-like 결과 — 성공이면 gatewayTxId, 실패면 reason.
 */
public interface PaymentGatewayPort {

    /**
     * 결제 시도. Mock PG 라도 idempotency 는 호출자(Service) 가 책임 (existsByTradeId).
     *
     * @param tradeId 거래 식별자 (PG 의 idempotency key 로 사용)
     * @param amountKrw 결제 금액 (KRW 정수원)
     * @return 성공/실패 결과
     */
    Result charge(Long tradeId, Long amountKrw);

    /**
     * 환불 — PAID 결제의 gatewayTxId 로 호출. 다음 PR 에서 사용.
     */
    Result refund(String gatewayTxId);

    /**
     * PG 응답 결과. Java record 기반 단순 sealed pattern.
     */
    record Result(boolean success, String gatewayTxId, String failureReason) {

        public static Result success(String gatewayTxId) {
            return new Result(true, gatewayTxId, null);
        }

        public static Result failure(String reason) {
            return new Result(false, null, reason);
        }
    }
}
