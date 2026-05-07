package com.portfolio.used_trade.chat.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 채팅방 생성 요청 — {@code POST /api/chat/rooms}.
 *
 * <p>buyerId 는 본문이 아닌 인증 컨텍스트에서 가져온다 — 위장 차단.
 */
public record ChatRoomCreateRequest(
        @NotNull(message = "productId 는 필수입니다.")
        @Positive(message = "productId 는 양수여야 합니다.")
        Long productId
) {
}
