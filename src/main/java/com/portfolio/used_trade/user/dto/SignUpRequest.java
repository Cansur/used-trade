package com.portfolio.used_trade.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청 DTO.
 *
 * <p>컨트롤러에서 {@code @Valid SignUpRequest body} 로 받으면 Spring 이
 * 아래 어노테이션을 자동 검증. 위반 시 {@code MethodArgumentNotValidException}
 * 가 던져지고 {@code GlobalExceptionHandler} 가 400 INVALID_INPUT 으로 변환.
 *
 * <p>비밀번호 정책: 영문 1자 이상 + 숫자 1자 이상, 8~64자.
 * 특수문자는 강제하지 않음 — 사용자 피로 ↑ vs 보안 마진은 미미.
 */
public record SignUpRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 64, message = "비밀번호는 8~64자여야 합니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "비밀번호는 영문과 숫자를 모두 포함해야 합니다."
        )
        String password,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.")
        String nickname
) {
}
