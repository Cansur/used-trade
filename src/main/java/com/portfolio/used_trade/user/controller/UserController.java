package com.portfolio.used_trade.user.controller;

import com.portfolio.used_trade.common.response.ApiResponse;
import com.portfolio.used_trade.user.dto.SignUpRequest;
import com.portfolio.used_trade.user.dto.UserResponse;
import com.portfolio.used_trade.user.security.AuthUser;
import com.portfolio.used_trade.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 관련 REST API.
 *
 * <p>현재 노출 API:
 * <ul>
 *   <li>POST /api/users     — 회원가입 (permitAll)</li>
 *   <li>GET  /api/users/me  — 내 정보 조회 (JWT 인증 필요)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 회원가입.
     *
     * <p>{@link Valid} — {@link SignUpRequest} 의 Bean Validation 어노테이션을 트리거.
     * 위반 시 {@code MethodArgumentNotValidException} → GlobalExceptionHandler 가
     * 400 INVALID_INPUT 으로 변환.
     *
     * <p>{@link ResponseStatus#code()} 201 Created — 새 리소스 생성 표준 응답.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ApiResponse.success(userService.signUp(request));
    }

    /**
     * 내 정보 조회.
     *
     * <p>{@link AuthUser} 는 {@code JwtAuthenticationFilter} 가 토큰 클레임으로부터
     * 미리 만들어 SecurityContext 에 박아둔 principal. 컨트롤러는 토큰 파싱이나 DB 조회
     * 없이 즉시 사용자 식별이 가능하다.
     *
     * <p>닉네임/createdAt 같이 토큰에 안 담긴 필드는 여기서 한 번 DB 조회로 채운다.
     */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal AuthUser auth) {
        return ApiResponse.success(userService.findById(auth.id()));
    }
}
