package com.portfolio.used_trade.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 로그아웃된 Access Token 의 jti 블랙리스트.
 *
 * <p><b>왜 stateless JWT 에 블랙리스트를 두는가?</b>
 * <ul>
 *   <li>Access Token 은 자체 검증만으로 통과 → 토큰을 빼앗긴 경우 만료까지 무력</li>
 *   <li>로그아웃 / 강제 만료를 즉시 반영하려면 별도 무효 목록 필요</li>
 *   <li>비용: 인증 요청마다 Redis EXISTS 1회 — 로컬 LAN 환경에서 무시 가능 수준</li>
 * </ul>
 *
 * <p><b>키 전략</b>: {@code blacklist:<jti>} — TTL 은 토큰의 잔여시간과 동일.
 * 토큰이 자연 만료되면 키도 사라지므로 메모리 누수 없음.
 *
 * <p>발급 → 블랙리스트 → 검증의 책임 분리:
 * <pre>
 * AuthService.logout()
 *   ├─ JwtTokenProvider.getJti(token)
 *   ├─ JwtTokenProvider.getRemainingMillis(token)
 *   └─ BlacklistService.blacklist(jti, remainingMs)
 *
 * JwtAuthenticationFilter
 *   ├─ JwtTokenProvider.parseClaims(token)
 *   └─ BlacklistService.isBlacklisted(jti) → 401 if true
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class BlacklistService {

    private static final String KEY_PREFIX = "blacklist:";
    /** 값 자체는 의미 없음 — 키 존재 여부만 사용. 가벼운 마커로 "1". */
    private static final String MARKER = "1";

    private final StringRedisTemplate redis;

    /**
     * jti 를 잔여시간 TTL 로 블랙리스트에 등록.
     *
     * <p>{@code ttlMs <= 0} 이면 이미 만료된 토큰 — 등록 의미 없고 Redis 가
     * 음수 Duration 에 {@link IllegalArgumentException} 을 던지므로 건너뛴다.
     */
    public void blacklist(String jti, long ttlMs) {
        if (ttlMs <= 0) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + jti, MARKER, Duration.ofMillis(ttlMs));
    }

    /**
     * 해당 jti 가 블랙리스트에 올라가 있으면 true.
     *
     * <p>{@link StringRedisTemplate#hasKey} 는 연결 이슈 등에서 {@code null} 을 반환할 수 있어
     * {@link Boolean#TRUE} 비교로 안전 처리한다 (null → false → 통과 허용 = 보수적 기본).
     */
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
    }
}
