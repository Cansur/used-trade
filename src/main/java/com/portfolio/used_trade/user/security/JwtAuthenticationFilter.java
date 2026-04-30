package com.portfolio.used_trade.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.common.response.ApiResponse;
import com.portfolio.used_trade.user.service.BlacklistService;
import com.portfolio.used_trade.user.service.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT Bearer 토큰을 검증해 SecurityContext 에 {@link AuthUser} 를 등록하는 필터.
 *
 * <p><b>실행 순서</b>: {@code UsernamePasswordAuthenticationFilter} 앞.
 * SecurityConfig 에서 {@code addFilterBefore} 로 등록.
 *
 * <p><b>흐름</b>
 * <pre>
 *   1. Authorization 헤더 없음 → 인증 미설정 + 다음 필터로 통과
 *      (이후 SecurityConfig 의 authenticated() 가 401 응답)
 *   2. Bearer 토큰 있음 → parseClaims 로 검증
 *   3. 검증 실패 (만료/위조/형식오류) → 401/그에 매핑된 코드로 즉시 응답 + 체인 중단
 *   4. jti 가 블랙리스트면 → 401 INVALID_TOKEN
 *   5. 통과 시 AuthUser principal + ROLE_<name> 권한 세팅 후 다음 필터로
 * </pre>
 *
 * <p><b>왜 GlobalExceptionHandler 가 안 잡나?</b>
 * 필터는 DispatcherServlet 앞에서 동작 → {@code @ControllerAdvice} 적용 범위 밖.
 * 그래서 여기서 직접 JSON 응답을 써준다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtTokenProvider jwtTokenProvider;
    private final BlacklistService blacklistService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();

        try {
            Claims claims = jwtTokenProvider.parseClaims(token);

            if (blacklistService.isBlacklisted(claims.getId())) {
                writeError(response, ErrorCode.INVALID_TOKEN);
                return;
            }

            authenticate(claims);
            chain.doFilter(request, response);

        } catch (BusinessException ex) {
            writeError(response, ex.getErrorCode());
        }
    }

    private void authenticate(Claims claims) {
        AuthUser principal = AuthUser.from(claims);
        var authorities = List.of(new SimpleGrantedAuthority(ROLE_PREFIX + principal.role().name()));
        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body = ApiResponse.error(code.getCode(), code.getDefaultMessage());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
