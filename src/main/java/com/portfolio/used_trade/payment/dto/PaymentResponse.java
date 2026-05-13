package com.portfolio.used_trade.payment.dto;

import com.portfolio.used_trade.payment.domain.Payment;
import com.portfolio.used_trade.payment.domain.PaymentStatus;

import java.time.LocalDateTime;

/**
 * 결제 단건 응답 DTO. Saga 결과 응답에 포함되거나 단독 조회 시 사용.
 */
public record PaymentResponse(
        Long id,
        Long tradeId,
        Long amount,
        PaymentStatus status,
        String gatewayTxId,
        LocalDateTime createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getTrade().getId(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getGatewayTxId(),
                payment.getCreatedAt()
        );
    }
}
