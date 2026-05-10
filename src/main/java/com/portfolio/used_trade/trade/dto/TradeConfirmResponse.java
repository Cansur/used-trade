package com.portfolio.used_trade.trade.dto;

import com.portfolio.used_trade.payment.dto.PaymentResponse;

/**
 * Saga confirm() 의 성공 응답 — 거래 + 결제 결과를 한 번에.
 *
 * <p>실패 (PG 응답 FAILED) 는 BusinessException(PAYMENT_FAILED) 로 throw 되므로
 * 본 record 는 항상 success=true.
 */
public record TradeConfirmResponse(
        TradeResponse trade,
        PaymentResponse payment
) {
}
