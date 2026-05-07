package com.portfolio.used_trade.chat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket 설정.
 *
 * <p><b>경로 컨벤션</b>
 * <ul>
 *   <li>핸드셰이크 endpoint : {@code /ws} (SockJS fallback 제공)</li>
 *   <li>클라이언트 SEND     : {@code /app/chat/rooms/{roomId}/messages}</li>
 *   <li>클라이언트 SUBSCRIBE : {@code /topic/chat/rooms/{roomId}}</li>
 * </ul>
 *
 * <p><b>왜 SimpleBroker 인가?</b>
 * 1차 PR 은 단일 서버 한정. STOMP SimpleBroker 는 앱 메모리 안의 in-process 브로커로 충분.
 * 다중 서버로 확장 시 같은 채팅방 사용자가 다른 인스턴스에 붙으면 메시지 유실 →
 * 다음 PR (chat-2) 에서 Redis Pub/Sub 으로 인스턴스 간 릴레이 추가 (ADR-3 시연).
 *
 * <p><b>왜 ChannelInterceptor 로 인증을 거는가?</b>
 * STOMP 메시지 채널은 HTTP 필터 (JwtAuthenticationFilter) 의 범위 밖.
 * {@link JwtChannelInterceptor} 가 CONNECT 단계에서 JWT 를 다시 검증해 세션 user 를 세팅하고,
 * SUBSCRIBE 단계에서 채팅방 참여자만 토픽을 구독할 수 있게 가드한다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // raw WebSocket — 통합 테스트(WebSocketStompClient + StandardWebSocketClient) 와
        // 모던 브라우저용. SockJS handshake 의 부속 핸들러 (info / iframe / xhr 폴백) 가
        // 끼지 않아 클라이언트 코드가 단순하다.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");

        // SockJS fallback — WebSocket 미지원 환경 (구형 브라우저 / 일부 프록시) 대비.
        // 데모/시연 단계라 모든 origin 허용. 운영 배포 시 도메인 화이트리스트로 좁힌다.
        registry.addEndpoint("/ws-sockjs")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor);
    }
}
