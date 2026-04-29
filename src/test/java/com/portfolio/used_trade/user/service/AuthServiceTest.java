package com.portfolio.used_trade.user.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.Role;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.dto.LoginRequest;
import com.portfolio.used_trade.user.dto.TokenResponse;
import com.portfolio.used_trade.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthService#login} 단위 테스트.
 *
 * <p>4개 시나리오:
 * <ol>
 *   <li>정상 — 토큰 발급 + Refresh Redis 저장</li>
 *   <li>미존재 이메일 — INVALID_PASSWORD (사용자 열거 방지)</li>
 *   <li>비번 불일치 — INVALID_PASSWORD</li>
 *   <li>SUSPENDED 계정 — INACTIVE_USER</li>
 * </ol>
 *
 * <p>실패 케이스에서는 부작용(토큰 발급 / Redis 저장)이 일어나지 않는지도 함께 검증한다 —
 * "조용한 실패" 회귀 방지.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 단위 테스트")
class AuthServiceTest {

    private static final long ACCESS_VALIDITY_MS = 30L * 60 * 1000;             // 30분
    private static final long REFRESH_VALIDITY_MS = 14L * 24 * 60 * 60 * 1000;  // 14일
    private static final JwtProperties PROPS = new JwtProperties(
            "test-secret-key-must-be-at-least-32-bytes-aaaaaaaa",
            ACCESS_VALIDITY_MS,
            REFRESH_VALIDITY_MS,
            "used-trade-test"
    );

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshTokenService refreshTokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // @InjectMocks 대신 명시적 생성 — 생성자 인자 순서가 눈에 보이고,
        // PROPS 같은 비-Mock 객체 주입 시 더 명확하다.
        authService = new AuthService(
                userRepository, passwordEncoder, jwtTokenProvider, refreshTokenService, PROPS
        );
    }

    @Test
    @DisplayName("정상 로그인 — Access/Refresh 발급 + RefreshTokenService.save 호출")
    void login_validCredentials_returnsTokensAndStoresRefresh() {
        // ── Arrange ──
        var request = new LoginRequest("a@b.com", "rawPass");
        User user = User.create("a@b.com", "HASHED", "tom");
        ReflectionTestUtils.setField(user, "id", 7L);

        given(userRepository.findByEmail("a@b.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("rawPass", "HASHED")).willReturn(true);
        given(jwtTokenProvider.createAccessToken(7L, "a@b.com", Role.USER)).willReturn("ACCESS_TOKEN");
        given(jwtTokenProvider.createRefreshToken(7L)).willReturn("REFRESH_TOKEN");

        // ── Act ──
        TokenResponse result = authService.login(request);

        // ── Assert : 반환값 ──
        assertThat(result.accessToken()).isEqualTo("ACCESS_TOKEN");
        assertThat(result.refreshToken()).isEqualTo("REFRESH_TOKEN");
        assertThat(result.accessTokenExpiresIn()).isEqualTo(ACCESS_VALIDITY_MS / 1000);   // 초 단위

        // ── Assert : 사이드이펙트 (Redis 저장) ──
        verify(refreshTokenService).save(7L, "REFRESH_TOKEN", REFRESH_VALIDITY_MS);
    }

    @Test
    @DisplayName("미존재 이메일 — INVALID_PASSWORD (이메일 존재 여부 노출 금지)")
    void login_unknownEmail_throwsInvalidPassword() {
        // ── Arrange ──
        given(userRepository.findByEmail("nope@b.com")).willReturn(Optional.empty());

        // ── Act & Assert ──
        assertThatThrownBy(() -> authService.login(new LoginRequest("nope@b.com", "any")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PASSWORD);

        // 미존재 이메일에 대해 password 비교 / 토큰 발급 / Redis 저장이 일어나면 안 됨
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtTokenProvider, never()).createAccessToken(any(), anyString(), any());
        verify(jwtTokenProvider, never()).createRefreshToken(any());
        verify(refreshTokenService, never()).save(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("비번 불일치 — INVALID_PASSWORD, 토큰 발급 안 함")
    void login_wrongPassword_throwsInvalidPassword() {
        // ── Arrange ──
        User user = User.create("a@b.com", "HASHED", "tom");
        ReflectionTestUtils.setField(user, "id", 7L);

        given(userRepository.findByEmail("a@b.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrong", "HASHED")).willReturn(false);

        // ── Act & Assert ──
        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "wrong")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PASSWORD);

        verify(jwtTokenProvider, never()).createAccessToken(any(), anyString(), any());
        verify(refreshTokenService, never()).save(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("정지된 계정 — INACTIVE_USER, 비번이 맞아도 로그인 거부")
    void login_suspendedUser_throwsInactiveUser() {
        // ── Arrange ──
        User user = User.create("a@b.com", "HASHED", "tom");
        ReflectionTestUtils.setField(user, "id", 7L);
        user.suspend();   // ACTIVE → SUSPENDED 로 상태 전이

        given(userRepository.findByEmail("a@b.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("rawPass", "HASHED")).willReturn(true);

        // ── Act & Assert ──
        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "rawPass")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INACTIVE_USER);

        // 비활성 사용자에게는 토큰 발급도, Redis 저장도 일어나면 안 됨
        verify(jwtTokenProvider, never()).createAccessToken(any(), anyString(), any());
        verify(jwtTokenProvider, never()).createRefreshToken(any());
        verify(refreshTokenService, never()).save(anyLong(), anyString(), anyLong());
    }
}
