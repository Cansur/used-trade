package com.portfolio.used_trade.chat.pubsub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.used_trade.chat.dto.MessageResponse;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 채팅 메시지를 Redis 채널 ({@link RedisChatChannels#CHAT_BROADCAST}) 로 publish.
 *
 * <p>같은 채널을 모든 인스턴스가 구독하므로, 한 인스턴스에서 publish 한 메시지는
 * 모든 인스턴스 (자기 자신 포함) 의 {@link RedisChatSubscriber} 가 수신해
 * 자기 STOMP broker 로 broadcast 한다 → 같은 방 사용자가 어느 인스턴스에 붙어 있어도
 * 메시지를 받는다 (ADR-3 의 핵심).
 *
 * <p>JSON 직렬화는 프로젝트 표준 {@link ObjectMapper} 를 사용해 LocalDateTime 포맷 등을 통일.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(Long roomId, MessageResponse message) {
        try {
            String payload = objectMapper.writeValueAsString(new ChatBroadcastEvent(roomId, message));
            redisTemplate.convertAndSend(RedisChatChannels.CHAT_BROADCAST, payload);
            log.debug("[redis.chat.publish] roomId={} messageId={}", roomId, message.id());
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 시스템 결함 — 호출자(컨트롤러) 에 INTERNAL 로 전파
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }
}
