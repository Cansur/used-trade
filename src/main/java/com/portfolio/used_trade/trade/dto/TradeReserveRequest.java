package com.portfolio.used_trade.trade.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 거래 예약 요청 — {@code POST /api/trades}.
 *
 * <p>buyerId 는 본문에서 받지 않는다 — 인증된 사용자 id 를 컨트롤러가 직접 주입한다.
 * 본문으로 받으면 다른 사람으로 위장 예약이 가능해진다.
 */
public record TradeReserveRequest(
        @NotNull(message = "productId 는 필수입니다.")
        @Positive(message = "productId 는 양수여야 합니다.")
        Long productId
) {
}
