package com.portfolio.used_trade.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 메시지 발화 요청. WebSocket STOMP 페이로드와 (1차에는) REST 양쪽에서 공용.
 *
 * <p>type 은 1차 PR 에서 항상 TEXT — 시스템 메시지는 향후 추가.
 */
public record MessageSendRequest(
        @NotBlank(message = "메시지 본문은 필수입니다.")
        @Size(max = 4000, message = "메시지는 4000자 이하여야 합니다.")
        String content
) {
}
