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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link RefreshTokenService} 단위 테스트.
 *
 * <p>실제 Redis 는 띄우지 않는다 — TestContainers 통합 테스트는 별도 영역.
 * 여기서는 키 컨벤션({@code refresh:<userId>}) + TTL 전달 + null 처리만 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService 단위 테스트")
class RefreshTokenServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(redis);
    }

    @Test
    @DisplayName("save — refresh:<userId> 키로 토큰을 저장하고 TTL 을 함께 건다")
    void save_putsTokenWithTtl() {
        given(redis.opsForValue()).willReturn(valueOps);

        service.save(7L, "the-refresh-token", 1_209_600_000L);

        verify(valueOps).set("refresh:7", "the-refresh-token", Duration.ofMillis(1_209_600_000L));
    }

    @Test
    @DisplayName("findByUserId — 키가 존재하면 Optional 로 감싸 반환")
    void findByUserId_whenExists_returnsOptional() {
        given(redis.opsForValue()).willReturn(valueOps);
        given(valueOps.get("refresh:7")).willReturn("stored-token");

        assertThat(service.findByUserId(7L)).contains("stored-token");
    }

    @Test
    @DisplayName("findByUserId — 키가 없으면 Optional.empty (만료/로그아웃 상태)")
    void findByUserId_whenMissing_returnsEmpty() {
        given(redis.opsForValue()).willReturn(valueOps);
        given(valueOps.get("refresh:7")).willReturn(null);

        assertThat(service.findByUserId(7L)).isEmpty();
    }

    @Test
    @DisplayName("delete — refresh:<userId> 키를 제거 (강제 로그아웃 / 재발급 회전)")
    void delete_removesKey() {
        service.delete(7L);

        verify(redis).delete("refresh:7");
    }
}
