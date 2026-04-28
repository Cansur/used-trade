package com.portfolio.used_trade.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 DTO.
 *
 * <p>가입 단계의 정책 검증({@code @Pattern}, {@code @Size(min=8)})은 일부러 안 둔다.
 * 정책이 바뀌어 8자 이하 기존 사용자가 있을 때 로그인까지 막히면 안 되기 때문.
 * 형식 검증(이메일/비어있음)만 수행하고, 실제 인증은 서비스 레이어가 담당.
 */
public record LoginRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {
}
