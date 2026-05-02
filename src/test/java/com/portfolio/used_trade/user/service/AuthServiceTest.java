package com.portfolio.used_trade.user.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.Role;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.dto.LoginRequest;
import com.portfolio.used_trade.user.dto.RefreshRequest;
import com.portfolio.used_trade.user.dto.TokenResponse;
import com.portfolio.used_trade.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
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
    @Mock private BlacklistService blacklistService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // @InjectMocks 대신 명시적 생성 — 생성자 인자 순서가 눈에 보이고,
        // PROPS 같은 비-Mock 객체 주입 시 더 명확하다.
        authService = new AuthService(
                userRepository, passwordEncoder, jwtTokenProvider,
                refreshTokenService, blacklistService, PROPS
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

    // ─────────────────────────────────────────────────────────────
    // refresh
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("refresh 정상 — Redis 매칭 + 활성 사용자 → 새 access 만 반환 (refresh 회전 X)")
    void refresh_validToken_returnsNewAccessOnly() {
        // ── Arrange ──
        String storedToken = "stored.refresh.token";
        Claims claims = mock(Claims.class);
        given(jwtTokenProvider.parseClaims(storedToken)).willReturn(claims);
        given(claims.getSubject()).willReturn("7");

        given(refreshTokenService.findByUserId(7L)).willReturn(Optional.of(storedToken));

        User user = User.create("a@b.com", "HASHED", "tom");
        ReflectionTestUtils.setField(user, "id", 7L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));

        given(jwtTokenProvider.createAccessToken(7L, "a@b.com", Role.USER)).willReturn("NEW_ACCESS");

        // ── Act ──
        TokenResponse result = authService.refresh(new RefreshRequest(storedToken));

        // ── Assert ──
        assertThat(result.accessToken()).isEqualTo("NEW_ACCESS");
        assertThat(result.refreshToken()).isNull();   // 회전 안 함 — null 로 응답
        assertThat(result.accessTokenExpiresIn()).isEqualTo(ACCESS_VALIDITY_MS / 1000);

        // 회전 안 하므로 새 refresh 발급 / Redis 재저장이 일어나면 안 됨
        verify(jwtTokenProvider, never()).createRefreshToken(any());
        verify(refreshTokenService, never()).save(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("refresh — parseClaims 가 던진 INVALID_TOKEN 은 그대로 전파")
    void refresh_invalidToken_propagatesError() {
        given(jwtTokenProvider.parseClaims("bad.token"))
                .willThrow(new BusinessException(ErrorCode.INVALID_TOKEN));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("bad.token")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        verify(refreshTokenService, never()).findByUserId(anyLong());
        verify(jwtTokenProvider, never()).createAccessToken(any(), anyString(), any());
    }

    @Test
    @DisplayName("refresh — Redis 에 토큰이 없으면 INVALID_TOKEN (이미 로그아웃됨)")
    void refresh_noStoredToken_throwsInvalidToken() {
        Claims claims = mock(Claims.class);
        given(jwtTokenProvider.parseClaims("orphan.token")).willReturn(claims);
        given(claims.getSubject()).willReturn("7");
        given(refreshTokenService.findByUserId(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("orphan.token")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("refresh — Redis 의 토큰과 다르면 INVALID_TOKEN (위조 / 회전 후 옛것)")
    void refresh_storedTokenMismatch_throwsInvalidToken() {
        Claims claims = mock(Claims.class);
        given(jwtTokenProvider.parseClaims("client.token")).willReturn(claims);
        given(claims.getSubject()).willReturn("7");
        given(refreshTokenService.findByUserId(7L)).willReturn(Optional.of("different.token.in.redis"));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("client.token")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        verify(jwtTokenProvider, never()).createAccessToken(any(), anyString(), any());
    }

    @Test
    @DisplayName("refresh — 토큰은 유효하지만 사용자가 탈퇴한 경우 USER_NOT_FOUND")
    void refresh_userNotFound_throws() {
        String token = "rt";
        Claims claims = mock(Claims.class);
        given(jwtTokenProvider.parseClaims(token)).willReturn(claims);
        given(claims.getSubject()).willReturn("7");
        given(refreshTokenService.findByUserId(7L)).willReturn(Optional.of(token));
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(token)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("refresh — SUSPENDED 사용자는 INACTIVE_USER")
    void refresh_suspendedUser_throws() {
        String token = "rt";
        Claims claims = mock(Claims.class);
        given(jwtTokenProvider.parseClaims(token)).willReturn(claims);
        given(claims.getSubject()).willReturn("7");
        given(refreshTokenService.findByUserId(7L)).willReturn(Optional.of(token));

        User user = User.create("a@b.com", "HASHED", "tom");
        ReflectionTestUtils.setField(user, "id", 7L);
        user.suspend();
        given(userRepository.findById(7L)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(token)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INACTIVE_USER);
    }

    // ─────────────────────────────────────────────────────────────
    // logout — 멱등 보장이 핵심
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("logout 정상 — jti 블랙리스트 + Redis refresh 삭제 모두 호출")
    void logout_validToken_blacklistsAndDeletes() {
        String accessToken = "valid.access.token";
        Claims claims = mock(Claims.class);
        long expirationMs = System.currentTimeMillis() + 600_000L;   // 10분 뒤 만료

        given(jwtTokenProvider.parseClaims(accessToken)).willReturn(claims);
        given(claims.getSubject()).willReturn("7");
        given(claims.getId()).willReturn("jti-abc");
        given(claims.getExpiration()).willReturn(new Date(expirationMs));

        // ── Act ──
        authService.logout(accessToken);

        // ── Assert ──
        // remaining 은 정확한 ms 매칭이 어려우므로 anyLong + 호출 자체만 검증
        verify(blacklistService).blacklist(eq("jti-abc"), anyLong());
        verify(refreshTokenService).delete(7L);
    }

    @Test
    @DisplayName("logout — Authorization 헤더가 비었을 때 (null/공백) 멱등 noop")
    void logout_nullOrBlankToken_isNoop() {
        authService.logout(null);
        authService.logout("");
        authService.logout("   ");

        verify(jwtTokenProvider, never()).parseClaims(anyString());
        verify(blacklistService, never()).blacklist(anyString(), anyLong());
        verify(refreshTokenService, never()).delete(anyLong());
    }

    @Test
    @DisplayName("logout — 무효/만료 토큰은 멱등 noop (이미 사용 불가하므로 추가 처리 의미 없음)")
    void logout_invalidToken_isNoop() {
        given(jwtTokenProvider.parseClaims("expired.token"))
                .willThrow(new BusinessException(ErrorCode.EXPIRED_TOKEN));

        // 예외를 삼키고 정상 종료해야 함 (200 OK 응답)
        authService.logout("expired.token");

        verify(blacklistService, never()).blacklist(anyString(), anyLong());
        verify(refreshTokenService, never()).delete(anyLong());
    }
}
