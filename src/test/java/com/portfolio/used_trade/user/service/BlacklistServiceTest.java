package com.portfolio.used_trade.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BlacklistService} 단위 테스트.
 *
 * <p>Access Token 로그아웃 시나리오 — 잔여시간만큼 jti 를 블랙리스트에 올리고,
 * 다음 요청에서 필터가 키 존재 여부로 거부할 수 있어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlacklistService 단위 테스트")
class BlacklistServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private BlacklistService service;

    @BeforeEach
    void setUp() {
        service = new BlacklistService(redis);
    }

    @Test
    @DisplayName("blacklist — blacklist:<jti> 키를 잔여시간 TTL 로 저장")
    void blacklist_putsKeyWithRemainingTtl() {
        given(redis.opsForValue()).willReturn(valueOps);

        service.blacklist("the-jti", 1_500_000L);

        verify(valueOps).set(eq("blacklist:the-jti"), eq("1"), eq(Duration.ofMillis(1_500_000L)));
    }

    @Test
    @DisplayName("blacklist — TTL 이 0 이하이면 (이미 만료된 토큰) 호출 자체를 건너뛴다")
    void blacklist_withNonPositiveTtl_skips() {
        // 이미 만료된 토큰을 블랙리스트에 올리는 건 의미 없음 + Redis 가 음수 Duration 에 예외
        service.blacklist("the-jti", 0);
        service.blacklist("the-jti", -10);

        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("isBlacklisted — 키가 존재하면 true")
    void isBlacklisted_whenKeyExists_returnsTrue() {
        given(redis.hasKey("blacklist:the-jti")).willReturn(Boolean.TRUE);

        assertThat(service.isBlacklisted("the-jti")).isTrue();
    }

    @Test
    @DisplayName("isBlacklisted — 키가 없으면 false")
    void isBlacklisted_whenKeyMissing_returnsFalse() {
        given(redis.hasKey("blacklist:the-jti")).willReturn(Boolean.FALSE);

        assertThat(service.isBlacklisted("the-jti")).isFalse();
    }

    @Test
    @DisplayName("isBlacklisted — Redis 가 null 을 반환해도 false 로 안전 처리")
    void isBlacklisted_whenRedisReturnsNull_returnsFalse() {
        // RedisTemplate.hasKey 는 Boolean (null 가능) — 연결 이슈 등에서 null 가능
        given(redis.hasKey("blacklist:the-jti")).willReturn(null);

        assertThat(service.isBlacklisted("the-jti")).isFalse();
    }
}
