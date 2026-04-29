package com.portfolio.used_trade.user.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.Role;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link JwtTokenProvider} 단위 테스트.
 *
 * <p>실제 jjwt 라이브러리를 그대로 사용하되, 외부 의존성은 없음.
 * {@link JwtProperties} 만 직접 주입해 다양한 시나리오 (만료, 다른 시크릿) 를 만든다.
 */
@DisplayName("JwtTokenProvider 단위 테스트")
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-bytes-aaaaaaaa";
    private static final String OTHER_SECRET = "another-secret-key-also-at-least-32-bytes-bbbbbbbbb";
    private static final long ACCESS_VALIDITY_MS = 30L * 60 * 1000;            // 30분
    private static final long REFRESH_VALIDITY_MS = 14L * 24 * 60 * 60 * 1000; // 14일
    private static final String ISSUER = "used-trade-test";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(
                new JwtProperties(SECRET, ACCESS_VALIDITY_MS, REFRESH_VALIDITY_MS, ISSUER)
        );
    }

    // ─────────────────────────────────────────────────────────────
    // create*
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Access Token 발급")
    class CreateAccessToken {

        @Test
        @DisplayName("sub/email/role/jti/iss 클레임을 포함하고 30분 후 만료된다")
        void containsExpectedClaims() {
            long beforeIssue = System.currentTimeMillis();

            String token = provider.createAccessToken(7L, "a@b.com", Role.USER);
            Claims claims = provider.parseClaims(token);

            assertThat(claims.getSubject()).isEqualTo("7");
            assertThat(claims.get("email", String.class)).isEqualTo("a@b.com");
            assertThat(claims.get("role", String.class)).isEqualTo("USER");
            assertThat(claims.getId()).isNotBlank();           // jti
            assertThat(claims.getIssuer()).isEqualTo(ISSUER);
            assertThat(claims.getExpiration().getTime())
                    .isCloseTo(beforeIssue + ACCESS_VALIDITY_MS, within(2_000L));
        }

        @Test
        @DisplayName("같은 사용자라도 호출마다 다른 jti 가 부여된다 — 블랙리스트 키 충돌 방지")
        void eachCallProducesUniqueJti() {
            String t1 = provider.createAccessToken(1L, "a@b.com", Role.USER);
            String t2 = provider.createAccessToken(1L, "a@b.com", Role.USER);

            assertThat(provider.getJti(t1)).isNotEqualTo(provider.getJti(t2));
        }
    }

    @Nested
    @DisplayName("Refresh Token 발급")
    class CreateRefreshToken {

        @Test
        @DisplayName("sub 만 포함하고 14일 후 만료된다")
        void containsExpectedClaims() {
            long beforeIssue = System.currentTimeMillis();

            String token = provider.createRefreshToken(42L);
            Claims claims = provider.parseClaims(token);

            assertThat(claims.getSubject()).isEqualTo("42");
            assertThat(claims.getExpiration().getTime())
                    .isCloseTo(beforeIssue + REFRESH_VALIDITY_MS, within(2_000L));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // parseClaims
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseClaims — 검증 실패 시 예외 매핑")
    class ParseClaims {

        @Test
        @DisplayName("다른 시크릿으로 발급된 토큰은 INVALID_TOKEN")
        void wrongSignature_throwsInvalidToken() {
            JwtTokenProvider attacker = new JwtTokenProvider(
                    new JwtProperties(OTHER_SECRET, ACCESS_VALIDITY_MS, REFRESH_VALIDITY_MS, ISSUER)
            );
            String foreignToken = attacker.createAccessToken(1L, "x@y.com", Role.USER);

            assertThatThrownBy(() -> provider.parseClaims(foreignToken))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("형식이 깨진 토큰은 INVALID_TOKEN")
        void malformed_throwsInvalidToken() {
            assertThatThrownBy(() -> provider.parseClaims("not-a-jwt"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("만료된 토큰은 EXPIRED_TOKEN")
        void expired_throwsExpiredToken() {
            // 발급 즉시 만료되도록 음수 validity 사용
            JwtTokenProvider expiringIssuer = new JwtTokenProvider(
                    new JwtProperties(SECRET, -1_000L, REFRESH_VALIDITY_MS, ISSUER)
            );
            String token = expiringIssuer.createAccessToken(1L, "a@b.com", Role.USER);

            assertThatThrownBy(() -> provider.parseClaims(token))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_TOKEN);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 블랙리스트용 헬퍼
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("블랙리스트 헬퍼")
    class BlacklistHelpers {

        @Test
        @DisplayName("getJti — 토큰의 jti 클레임 그대로 반환")
        void getJti_returnsClaimsId() {
            String token = provider.createAccessToken(1L, "a@b.com", Role.USER);

            String jti = provider.getJti(token);

            assertThat(jti).isEqualTo(provider.parseClaims(token).getId());
        }

        @Test
        @DisplayName("getRemainingMillis — 새로 발급한 토큰은 거의 유효시간만큼 남는다")
        void getRemainingMillis_freshToken_isNearValidity() {
            String token = provider.createAccessToken(1L, "a@b.com", Role.USER);

            long remaining = provider.getRemainingMillis(token);

            assertThat(remaining)
                    .isPositive()
                    .isLessThanOrEqualTo(ACCESS_VALIDITY_MS)
                    .isGreaterThan(ACCESS_VALIDITY_MS - 5_000);
        }
    }
}
