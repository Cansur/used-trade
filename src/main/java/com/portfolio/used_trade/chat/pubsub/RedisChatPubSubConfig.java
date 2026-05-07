package com.portfolio.used_trade.chat.pubsub;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 인프라 설정 — chat 도메인 한정.
 *
 * <p>{@link RedisMessageListenerContainer} 를 한 인스턴스만 두고
 * {@link RedisChatSubscriber} 를 채널 {@link RedisChatChannels#CHAT_BROADCAST} 에 등록.
 * Spring Boot 의 Redis auto-configuration 이 제공하는 {@link RedisConnectionFactory} 를 그대로 사용.
 *
 * <p>다른 도메인이 Pub/Sub 을 추가하면 이 컨테이너에 listener 를 추가 등록 — 컨테이너를
 * 도메인마다 분리하는 건 자원 낭비.
 */
@Configuration
@RequiredArgsConstructor
public class RedisChatPubSubConfig {

    private final RedisConnectionFactory connectionFactory;
    private final RedisChatSubscriber chatSubscriber;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(chatSubscriber, new PatternTopic(RedisChatChannels.CHAT_BROADCAST));
        return container;
    }
}
