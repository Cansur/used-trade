package com.portfolio.used_trade.trade.dto;

import com.portfolio.used_trade.trade.domain.Trade;
import com.portfolio.used_trade.trade.domain.TradeStatus;

import java.time.LocalDateTime;

/**
 * 거래 단건 응답 DTO.
 *
 * <p>{@link Trade} 의 LAZY 연관(product / buyer)을 트랜잭션 내에서 풀어
 * 컨트롤러로 전달한다. {@code spring.jpa.open-in-view=false} 환경에서 컨트롤러는
 * 트랜잭션 밖이므로, LAZY 접근을 서비스 레이어에서 마쳐야 한다.
 */
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
                trade.getProduct().getId(),
                trade.getBuyer().getId(),
                trade.getProduct().getSeller().getId(),
                trade.getPricePaid(),
                trade.getStatus(),
                trade.getCreatedAt()
        );
    }
}
