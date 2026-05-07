package com.portfolio.used_trade.chat.controller;

import com.portfolio.used_trade.chat.dto.MessageResponse;
import com.portfolio.used_trade.chat.dto.MessageSendRequest;
import com.portfolio.used_trade.chat.service.ChatService;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.security.AuthUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * STOMP 메시지 라우팅 — 클라이언트가 {@code /app/chat/rooms/{roomId}/messages} 로 SEND 한
 * 페이로드를 받아 영속화 후 {@code /topic/chat/rooms/{roomId}} 로 브로드캐스트.
 *
 * <p>참여자 가드는 두 단계로 박혀 있다:
 * <ol>
 *   <li>SUBSCRIBE 단계 — {@link com.portfolio.used_trade.chat.config.JwtChannelInterceptor}
 *       가 토픽 구독 시점에 참여자 검증</li>
 *   <li>SEND 단계 — {@link ChatService#sendMessage} 가 도메인 메서드 ({@code Message.send})
 *       로 비참여자 발화를 차단</li>
 * </ol>
 *
 * <p>1차 PR 은 단일 서버 한정. 다중 서버로 확장 시 SimpleBroker 의 in-process 한계로
 * 같은 방 사용자가 다른 인스턴스에 붙으면 메시지 유실 → 다음 PR 에서 Redis Pub/Sub 추가.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 메시지 발화 — STOMP SEND.
     *
     * <p>{@link Principal} 은 STOMP 세션의 user — JwtChannelInterceptor 가 CONNECT 시점에
     * 세팅한 {@link Authentication} 이다. 본문 검증/참여자 검증은 ChatService 에 위임.
     */
    @MessageMapping("/chat/rooms/{roomId}/messages")
    public void sendMessage(
            @DestinationVariable Long roomId,
            @Payload MessageSendRequest request,
            Principal principal
    ) {
        AuthUser user = extractAuthUser(principal);
        MessageResponse response = chatService.sendMessage(user.id(), roomId, request.content());
        messagingTemplate.convertAndSend("/topic/chat/rooms/" + roomId, response);
        log.debug("[ws.send] roomId={} senderId={} messageId={}", roomId, user.id(), response.id());
    }

    /**
     * STOMP 메시지 핸들러에서 발생한 BusinessException 을 사용자에게 전송.
     *
     * <p>Spring 은 {@code /user/queue/errors} 같은 user destination 으로 회신하지만,
     * 1차에는 단순화를 위해 server log 만 남긴다. 향후 클라이언트 UX 개선 시 errors 큐로 이전.
     */
    @MessageExceptionHandler(BusinessException.class)
    public void handleBusinessException(BusinessException ex, Principal principal) {
        log.warn("[ws.error] code={} principal={}", ex.getErrorCode(), principal == null ? "null" : principal.getName(), ex);
    }

    private static AuthUser extractAuthUser(Principal principal) {
        if (principal instanceof Authentication auth && auth.getPrincipal() instanceof AuthUser user) {
            return user;
        }
        // CONNECT 인터셉터를 통과했는데 여기까지 오면 설정 결함
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
}
