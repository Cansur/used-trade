package com.portfolio.used_trade.chat.config;

import com.portfolio.used_trade.chat.service.ChatService;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.security.AuthUser;
import com.portfolio.used_trade.user.service.BlacklistService;
import com.portfolio.used_trade.user.service.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * STOMP CONNECT 단계에서 JWT 를 검증해 세션의 user principal 을 세팅하고,
 * SUBSCRIBE 단계에서 채팅방 참여자인지 가드하는 채널 인터셉터.
 *
 * <p><b>왜 JwtAuthenticationFilter 와 따로 두는가?</b>
 * HTTP 필터는 DispatcherServlet 앞에서 동작 — STOMP 메시지 채널은 WebSocket
 * 핸드셰이크 이후 별도 채널이라 HTTP 필터 범위 밖. 같은 검증 로직 ({@link JwtTokenProvider},
 * {@link BlacklistService}) 을 STOMP 측에서 다시 호출해 세션 단계의 인증을 강제한다.
 *
 * <p><b>커맨드별 책임</b>
 * <ul>
 *   <li>{@code CONNECT}    : Authorization 헤더의 Bearer 검증 → 세션 user 세팅</li>
 *   <li>{@code SUBSCRIBE}  : destination 이 {@code /topic/chat/rooms/{roomId}} 면 참여자 검증</li>
 *   <li>{@code SEND} / 그 외 : 검증은 {@code @MessageMapping} 컨트롤러 + ChatService 도메인 가드 위임</li>
 * </ul>
 *
 * <p>여기서 던진 {@link BusinessException} 은 STOMP ERROR 프레임으로 클라이언트에 전달된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final String CHAT_TOPIC_PREFIX = "/topic/chat/rooms/";

    private final JwtTokenProvider jwtTokenProvider;
    private final BlacklistService blacklistService;
    private final ChatService chatService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        switch (accessor.getCommand()) {
            case CONNECT -> handleConnect(accessor);
            case SUBSCRIBE -> handleSubscribe(accessor);
            default -> { /* SEND, DISCONNECT 등은 컨트롤러/서비스가 가드 */ }
        }
        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String rawToken = header.substring(BEARER_PREFIX.length()).trim();

        Claims claims = jwtTokenProvider.parseClaims(rawToken);
        if (blacklistService.isBlacklisted(claims.getId())) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        AuthUser principal = AuthUser.from(claims);
        var authorities = List.of(new SimpleGrantedAuthority(ROLE_PREFIX + principal.role().name()));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        accessor.setUser(auth);
        log.debug("[ws.connect] userId={} email={}", principal.id(), principal.email());
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(CHAT_TOPIC_PREFIX)) {
            // chat 외 토픽은 가드 대상 아님 (현재는 chat 만 사용)
            return;
        }
        Authentication auth = (Authentication) accessor.getUser();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser principal)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        String suffix = destination.substring(CHAT_TOPIC_PREFIX.length());
        long roomId;
        try {
            roomId = Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        chatService.assertParticipant(principal.id(), roomId);
        log.debug("[ws.subscribe] userId={} roomId={}", principal.id(), roomId);
    }
}
