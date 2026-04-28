package com.portfolio.used_trade.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Access Token 재발급 요청 DTO.
 *
 * <p>클라이언트가 보관 중이던 Refresh Token 을 그대로 보내면, 서버가 검증 후
 * 새 Access Token 을 돌려준다. (Refresh Token 자체의 회전(rotation) 은 Phase 2 옵션)
 */
public record RefreshRequest(

        @NotBlank(message = "Refresh Token 은 필수입니다.")
        String refreshToken
) {
}
