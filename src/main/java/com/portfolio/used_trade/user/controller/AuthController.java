package com.portfolio.used_trade.user.controller;

import com.portfolio.used_trade.common.response.ApiResponse;
import com.portfolio.used_trade.user.dto.LoginRequest;
import com.portfolio.used_trade.user.dto.TokenResponse;
import com.portfolio.used_trade.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API.
 *
 * <p><b>왜 {@link UserController} 와 분리했나?</b>
 * <ul>
 *   <li>도메인 책임이 다름: User = 정보 / Auth = 세션 라이프사이클</li>
 *   <li>경로 prefix 가 다름: {@code /api/users} vs {@code /api/auth}</li>
 *   <li>SecurityConfig 에서 {@code /api/auth/**} 를 permitAll 로 열어주기 명확함</li>
 * </ul>
 *
 * <p>현재 노출 API:
 * <ul>
 *   <li>POST /api/auth/login — 로그인 (이메일 + 비번 → 토큰 페어)</li>
 * </ul>
 *
 * <p>(이후) refresh / logout 추가 예정.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 로그인.
     *
     * <p>응답 200 OK + {@link ApiResponse} 래핑된 {@link TokenResponse}.
     * 실패 (INVALID_PASSWORD / INACTIVE_USER) 는 {@code GlobalExceptionHandler} 가
     * {@link com.portfolio.used_trade.common.exception.ErrorCode} 의 HTTP 상태로 변환.
     */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }
}
