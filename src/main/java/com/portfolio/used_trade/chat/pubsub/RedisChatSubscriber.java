package com.portfolio.used_trade.chat.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis 채널 {@link RedisChatChannels#CHAT_BROADCAST} 의 메시지를 받아
 * 자기 인스턴스의 STOMP broker 로 broadcast 한다.
 *
 * <p><b>흐름</b>
 * <pre>
 *   인스턴스 A: client.SEND → ChatService 영속화 → RedisChatPublisher.publish
 *                              ↓
 *                    Redis chat.broadcast 채널
 *                       ↓                ↓
 *           인스턴스 A.subscriber  인스턴스 B.subscriber
 *                       ↓                ↓
 *              SimpMessagingTemplate.convertAndSend("/topic/chat/rooms/{id}")
 *                       ↓                ↓
 *            A 에 붙은 SUBSCRIBER  B 에 붙은 SUBSCRIBER
 * </pre>
 *
 * <p>자기 자신에게도 도달 — publisher 가 보낸 메시지를 자기 인스턴스 subscriber 도 받는다.
 * 이게 단일 인스턴스 / 다중 인스턴스 코드 경로를 동일하게 유지하는 핵심.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            ChatBroadcastEvent event = objectMapper.readValue(body, ChatBroadcastEvent.class);
            messagingTemplate.convertAndSend(
                    "/topic/chat/rooms/" + event.roomId(), event.message());
            log.debug("[redis.chat.relay] roomId={} messageId={}",
                    event.roomId(), event.message().id());
        } catch (Exception e) {
            // 한 메시지 파싱 실패가 listener 자체를 죽이지 않도록 catch — 운영 환경에선 alert 연동.
            log.error("[redis.chat.relay] failed to deserialize/relay payload", e);
        }
    }
}
