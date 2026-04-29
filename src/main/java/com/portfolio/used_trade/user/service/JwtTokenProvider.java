package com.portfolio.used_trade.user.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 토큰 발급/검증 책임만 가진 단일 컴포넌트.
 *
 * <p><b>책임</b>
 * <ul>
 *   <li>Access / Refresh 토큰 생성</li>
 *   <li>서명 검증 + 클레임 파싱</li>
 *   <li>로그아웃 블랙리스트 운용을 위한 jti / 잔여시간 추출</li>
 * </ul>
 *
 * <p><b>경계</b>
 * <ul>
 *   <li>Redis 블랙리스트 조회/저장은 여기서 안 함 → {@code RefreshTokenService} /
 *       {@code AuthService} 가 본 클래스를 조립해서 사용</li>
 *   <li>{@link Role} 은 enum {@code name()} 문자열로 직렬화 → 역직렬화 안전성 확보</li>
 * </ul>
 *
 * <p><b>예외 매핑</b>
 * <ul>
 *   <li>{@link ExpiredJwtException} → {@link ErrorCode#EXPIRED_TOKEN}</li>
 *   <li>그 외 {@link JwtException} 계열 + {@link IllegalArgumentException} (빈 문자열 등)
 *       → {@link ErrorCode#INVALID_TOKEN}</li>
 * </ul>
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final long accessTokenValidityMs;
    private final long refreshTokenValidityMs;
    private final String issuer;

    public JwtTokenProvider(JwtProperties props) {
        // jjwt 0.12+ 는 HMAC-SHA256 서명에 32바이트 이상의 키를 강제 (짧으면 WeakKeyException).
        this.signingKey = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidityMs = props.accessTokenValidityMs();
        this.refreshTokenValidityMs = props.refreshTokenValidityMs();
        this.issuer = props.issuer();
    }

    /**
     * Access Token 발급.
     *
     * <p>jti (= UUID) 는 로그아웃 시 Redis 블랙리스트 키로 사용된다.
     * 같은 사용자라도 발급마다 jti 가 달라 강제 로그아웃 단위가 토큰 단위로 명확해진다.
     */
    public String createAccessToken(Long userId, String email, Role role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenValidityMs);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .issuer(issuer)
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Refresh Token 발급. 권한 정보는 담지 않는다 — 재발급에 필요한 식별자만.
     */
    public String createRefreshToken(Long userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenValidityMs);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(issuer)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 서명 검증 + payload 파싱.
     *
     * @throws BusinessException {@link ErrorCode#EXPIRED_TOKEN} 토큰 만료
     * @throws BusinessException {@link ErrorCode#INVALID_TOKEN} 서명 불일치 / 형식 오류 / 빈 입력
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new BusinessException(ErrorCode.EXPIRED_TOKEN, ex);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, ex);
        }
    }

    /** 블랙리스트 키로 사용할 jti 추출. 토큰이 무효면 {@link #parseClaims}가 예외를 던진다. */
    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    /**
     * 토큰 만료까지 남은 시간 (ms). Redis 블랙리스트 TTL 설정에 사용.
     *
     * <p>이미 만료된 토큰은 {@link #parseClaims} 단계에서
     * {@link ErrorCode#EXPIRED_TOKEN} 으로 거부된다 — 호출자는 유효 토큰만 넘긴다고 가정.
     */
    public long getRemainingMillis(String token) {
        Claims claims = parseClaims(token);
        return claims.getExpiration().getTime() - System.currentTimeMillis();
    }
}
