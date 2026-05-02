package com.portfolio.used_trade.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Refresh Token 의 Redis 저장소.
 *
 * <p><b>키 전략</b>: {@code refresh:<userId>} — 사용자당 단일 디바이스 가정.
 * 다중 디바이스가 필요해지면 {@code refresh:<userId>:<deviceId>} 로 확장.
 *
 * <p><b>왜 Redis 인가?</b>
 * <ul>
 *   <li>강제 로그아웃 / 재발급 시 즉시 무효화 가능 (DB row 삭제 대비 가벼움)</li>
 *   <li>TTL = 14일 자동 만료 → 별도 청소 작업 불필요</li>
 *   <li>레이턴시 mass: 인증 경로에 들어가도 됨</li>
 * </ul>
 *
 * <p>실제 Redis 동작은 통합 테스트 (TestContainers) 에서 검증한다.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redis;

    /** 발급된 Refresh Token 을 TTL 과 함께 저장. 같은 키가 있으면 덮어쓴다 (회전). */
    public void save(Long userId, String refreshToken, long ttlMs) {
        redis.opsForValue().set(key(userId), refreshToken, Duration.ofMillis(ttlMs));
    }

    /** 저장된 Refresh Token 조회. 만료 또는 로그아웃 상태면 빈 Optional. */
    public Optional<String> findByUserId(Long userId) {
        return Optional.ofNullable(redis.opsForValue().get(key(userId)));
    }

    /** 강제 로그아웃 / 토큰 회전 시 저장된 Refresh Token 폐기. */
    public void delete(Long userId) {
        redis.delete(key(userId));
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
