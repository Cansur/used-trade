package com.portfolio.used_trade.user.controller;

import com.portfolio.used_trade.common.response.ApiResponse;
import com.portfolio.used_trade.user.dto.LoginRequest;
import com.portfolio.used_trade.user.dto.RefreshRequest;
import com.portfolio.used_trade.user.dto.TokenResponse;
import com.portfolio.used_trade.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 *   <li>POST /api/auth/login   — 로그인 (이메일 + 비번 → 토큰 페어)</li>
 *   <li>POST /api/auth/refresh — Refresh Token 으로 새 Access 발급</li>
 *   <li>POST /api/auth/logout  — Access jti 블랙리스트 + Redis Refresh 삭제 (멱등)</li>
 * </ul>
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

    /**
     * Refresh Token 으로 새 Access Token 만 재발급.
     * 응답의 {@code refreshToken} 은 회전을 안 하므로 항상 {@code null} (Jackson 설정으로 JSON 에서 제외).
     */
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.success(authService.refresh(request));
    }

    /**
     * 로그아웃 — 멱등 보장.
     *
     * <p>{@code Authorization} 헤더에서 Access Token 을 추출해 서비스로 위임.
     * 헤더가 없거나 토큰이 무효해도 200 OK 를 반환한다 (사용자 입장에서 로그아웃은 항상 성공).
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        String token = extractBearer(authorization);
        authService.logout(token);
        return ApiResponse.ok();
    }

    private static String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }
}
