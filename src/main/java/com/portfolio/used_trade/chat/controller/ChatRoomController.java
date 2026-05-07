package com.portfolio.used_trade.chat.controller;

import com.portfolio.used_trade.chat.dto.ChatRoomCreateRequest;
import com.portfolio.used_trade.chat.dto.ChatRoomResponse;
import com.portfolio.used_trade.chat.dto.MessageCursorPageResponse;
import com.portfolio.used_trade.chat.service.ChatService;
import com.portfolio.used_trade.common.response.ApiResponse;
import com.portfolio.used_trade.user.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 채팅방 / 메시지 REST API.
 *
 * <p>현재 노출:
 * <ul>
 *   <li>POST /api/chat/rooms                       — 채팅방 생성 (또는 기존 방 재사용, 200/201)</li>
 *   <li>GET  /api/chat/rooms                       — 내 채팅방 목록 (구매자/판매자 양쪽)</li>
 *   <li>GET  /api/chat/rooms/{roomId}/messages     — 방 메시지 커서 페이징 (참여자 전용)</li>
 * </ul>
 *
 * <p>실시간 메시지 송수신은 WebSocket + STOMP 가 담당 (별도 컨트롤러).
 *
 * <p>Security: SecurityConfig 의 {@code anyRequest().authenticated()} 정책에 자연스럽게
 * 잡힌다 — Bearer 헤더 없으면 401.
 */
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatService chatService;

    /**
     * 채팅방 생성. 같은 (product, buyer) 페어에 이미 방이 있으면 기존 방을 그대로 반환.
     *
     * <p>새로 만들든 재사용하든 본문에는 항상 ChatRoomResponse. 1차 PR 은 단순화를 위해
     * 항상 201 로 반환 (재사용 케이스도 동일 자원 식별자 노출 의미).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChatRoomResponse> createRoom(
            @AuthenticationPrincipal AuthUser auth,
            @Valid @RequestBody ChatRoomCreateRequest request
    ) {
        return ApiResponse.success(chatService.createOrGetRoom(auth.id(), request.productId()));
    }

    /**
     * 내 채팅방 목록 — buyer 또는 seller 시점 합집합.
     */
    @GetMapping
    public ApiResponse<List<ChatRoomResponse>> listMyRooms(
            @AuthenticationPrincipal AuthUser auth
    ) {
        return ApiResponse.success(chatService.listMyRooms(auth.id()));
    }

    /**
     * 방 메시지 목록 (커서 페이징, 최신순).
     *
     * @param cursor 앞 페이지 마지막 메시지 id (생략 시 첫 페이지)
     * @param size   페이지 크기 (기본 20, 1~100 클램핑)
     */
    @GetMapping("/{roomId}/messages")
    public ApiResponse<MessageCursorPageResponse> listMessages(
            @AuthenticationPrincipal AuthUser auth,
            @PathVariable Long roomId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(chatService.listMessages(auth.id(), roomId, cursor, size));
    }
}
