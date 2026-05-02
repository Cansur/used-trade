package com.portfolio.used_trade.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.Role;
import com.portfolio.used_trade.user.service.BlacklistService;
import com.portfolio.used_trade.user.service.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link JwtAuthenticationFilter} 단위 테스트.
 *
 * <p>4 시나리오:
 * <ol>
 *   <li>Authorization 헤더 없음 — SecurityContext 비워둔 채 그대로 통과 (이후 SecurityConfig 가 401)</li>
 *   <li>유효 토큰 — AuthUser principal + ROLE_USER 권한 세팅 후 통과</li>
 *   <li>블랙리스트 jti — 401 응답 + 체인 중단</li>
 *   <li>만료 토큰 — 401 응답 + 체인 중단</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter 단위 테스트")
class JwtAuthenticationFilterTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private BlacklistService blacklistService;
    @Mock private FilterChain chain;
    @Mock private Claims claims;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        // 운영에선 Spring-Boot 자동 구성된 ObjectMapper 가 주입되어 LocalDateTime 처리 가능.
        // 단위 테스트에선 raw ObjectMapper 라 JavaTimeModule 을 직접 등록.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new JwtAuthenticationFilter(jwtTokenProvider, blacklistService, objectMapper);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();   // 테스트 간 격리
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 — 인증 미설정 상태로 다음 필터로 통과")
    void noAuthHeader_passesThroughWithoutAuthentication() throws Exception {
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);   // 필터 자체는 응답 안 만짐
    }

    @Test
    @DisplayName("유효한 Bearer 토큰 — AuthUser principal + ROLE_USER 권한 세팅 후 통과")
    void validBearerToken_setsAuthUserPrincipal() throws Exception {
        // ── Arrange ──
        request.addHeader("Authorization", "Bearer valid.jwt.token");
        given(jwtTokenProvider.parseClaims("valid.jwt.token")).willReturn(claims);
        given(claims.getSubject()).willReturn("7");
        given(claims.get("email", String.class)).willReturn("a@b.com");
        given(claims.get("role", String.class)).willReturn("USER");
        given(claims.getId()).willReturn("jti-123");
        given(blacklistService.isBlacklisted("jti-123")).willReturn(false);

        // ── Act ──
        filter.doFilter(request, response, chain);

        // ── Assert ──
        verify(chain).doFilter(request, response);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();

        AuthUser principal = (AuthUser) auth.getPrincipal();
        assertThat(principal.id()).isEqualTo(7L);
        assertThat(principal.email()).isEqualTo("a@b.com");
        assertThat(principal.role()).isEqualTo(Role.USER);

        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("블랙리스트된 jti — 401 응답 + 다음 필터로 진행 안 함")
    void blacklistedJti_writes401AndStopsChain() throws Exception {
        // ── Arrange ──
        request.addHeader("Authorization", "Bearer logged.out.token");
        given(jwtTokenProvider.parseClaims("logged.out.token")).willReturn(claims);
        given(claims.getId()).willReturn("jti-blacklisted");
        given(blacklistService.isBlacklisted("jti-blacklisted")).willReturn(true);

        // ── Act ──
        filter.doFilter(request, response, chain);

        // ── Assert ──
        assertThat(response.getStatus()).isEqualTo(ErrorCode.INVALID_TOKEN.getStatus().value());
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("INVALID_TOKEN");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("/api/auth/** 경로는 필터 자체를 건너뛴다 — refresh/logout 멱등 보장")
    void authEndpoints_areSkipped() throws Exception {
        request.setRequestURI("/api/auth/logout");
        request.addHeader("Authorization", "Bearer would-be-blacklisted-token");

        filter.doFilter(request, response, chain);

        // 필터 진입 자체가 없어야 — parseClaims/blacklist 둘 다 호출 X
        verify(jwtTokenProvider, never()).parseClaims(anyString());
        verify(blacklistService, never()).isBlacklisted(anyString());
        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("만료된 토큰 — 401 EXPIRED_TOKEN 응답 + 체인 중단")
    void expiredToken_writes401AndStopsChain() throws Exception {
        // ── Arrange ──
        request.addHeader("Authorization", "Bearer expired.token");
        given(jwtTokenProvider.parseClaims("expired.token"))
                .willThrow(new BusinessException(ErrorCode.EXPIRED_TOKEN));

        // ── Act ──
        filter.doFilter(request, response, chain);

        // ── Assert ──
        assertThat(response.getStatus()).isEqualTo(ErrorCode.EXPIRED_TOKEN.getStatus().value());
        assertThat(response.getContentAsString()).contains("EXPIRED_TOKEN");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(any(), any());
    }
}
