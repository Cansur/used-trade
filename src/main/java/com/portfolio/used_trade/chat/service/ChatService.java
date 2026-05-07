package com.portfolio.used_trade.chat.service;

import com.portfolio.used_trade.chat.domain.ChatRoom;
import com.portfolio.used_trade.chat.domain.Message;
import com.portfolio.used_trade.chat.domain.MessageType;
import com.portfolio.used_trade.chat.dto.ChatRoomResponse;
import com.portfolio.used_trade.chat.dto.MessageCursorPageResponse;
import com.portfolio.used_trade.chat.dto.MessageResponse;
import com.portfolio.used_trade.chat.repository.ChatRoomRepository;
import com.portfolio.used_trade.chat.repository.MessageRepository;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 채팅 도메인 핵심 비즈니스 로직.
 *
 * <p>책임:
 * <ul>
 *   <li>채팅방 생성 — (product, buyer) 중복 시 기존 방 재사용 (UNIQUE 제약 + existsBy 가드)</li>
 *   <li>채팅방 목록 — 내가 buyer 또는 seller 인 방을 한 번에 fetch</li>
 *   <li>메시지 발화 — 참여자 가드 위임 (도메인 메서드)</li>
 *   <li>메시지 페이징 — 방 참여자만 조회 가능, 커서 + size+1 트릭</li>
 * </ul>
 *
 * <p><b>왜 클래스에 {@code @Transactional(readOnly = true)} 인가?</b>
 * 읽기 전용 메서드는 dirty checking 을 건너뛰어 약간 빠르고, 쓰기 메서드는 메서드 단위로
 * {@code @Transactional} 을 다시 걸어 의도를 명시한다 — UserService / ProductService 와 동일.
 *
 * <p><b>왜 응답 변환을 서비스에서 하는가?</b>
 * {@code spring.jpa.open-in-view=false} 환경에서 컨트롤러는 트랜잭션 밖. LAZY 연관을
 * 거기서 접근하면 LazyInitializationException → Service 가 트랜잭션 종료 전 펼친다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    /** 메시지 페이지 최대 크기 — 악의적/실수 입력 클램핑. */
    static final int MAX_PAGE_SIZE = 100;
    /** 메시지 페이지 최소 크기 — 0 이나 음수 방어. */
    static final int MIN_PAGE_SIZE = 1;
    /** 메시지 본문 최대 길이 — DTO @Size 와 동일하지만 STOMP 경로처럼 DTO 검증을 못 거치는 경우를 위해 서비스에서도 가드. */
    static final int MAX_CONTENT_LENGTH = 4_000;

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    // ---------- 채팅방 ----------

    /**
     * 채팅방 생성 또는 기존 방 재사용.
     *
     * <p>같은 (product, buyer) 페어에 이미 방이 있으면 새로 만들지 않고 기존 방을 그대로
     * 반환한다 — 사용자 관점에서 동일한 "그 상품 그 구매자" 와의 채팅은 한 곳이어야
     * 자연스럽기 때문. UNIQUE 제약은 race 시 마지막 방어선.
     *
     * @throws BusinessException {@link ErrorCode#USER_NOT_FOUND}            buyer 부재
     * @throws BusinessException {@link ErrorCode#PRODUCT_NOT_FOUND}         product 부재
     * @throws BusinessException {@link ErrorCode#CHAT_SELF_NOT_ALLOWED}     본인 상품에 시도
     */
    @Transactional
    public ChatRoomResponse createOrGetRoom(Long buyerId, Long productId) {
        User buyer = userRepository.findById(buyerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        ChatRoom room = chatRoomRepository.findByProductIdAndBuyerId(productId, buyerId)
                .orElseGet(() -> chatRoomRepository.save(ChatRoom.create(product, buyer)));
        return ChatRoomResponse.from(room);
    }

    /**
     * 내 채팅방 목록 — buyer 또는 seller 시점.
     */
    public List<ChatRoomResponse> listMyRooms(Long userId) {
        return chatRoomRepository.findRoomsForUser(userId).stream()
                .map(ChatRoomResponse::from)
                .toList();
    }

    // ---------- 메시지 ----------

    /**
     * 메시지 발화 — 참여자 가드는 도메인 메서드 {@link Message#send} 가 책임.
     *
     * @throws BusinessException {@link ErrorCode#USER_NOT_FOUND}             sender 부재
     * @throws BusinessException {@link ErrorCode#CHAT_ROOM_NOT_FOUND}        room 부재
     * @throws BusinessException {@link ErrorCode#NOT_CHAT_PARTICIPANT}       비참여자 발화
     */
    @Transactional
    public MessageResponse sendMessage(Long senderId, Long roomId, String content) {
        // STOMP 경로처럼 @Valid 검증을 못 거치는 경우를 위해 서비스에서도 본문 길이/공백 가드
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        Message saved = messageRepository.save(Message.send(room, sender, content, MessageType.TEXT));
        return MessageResponse.from(saved);
    }

    /**
     * 채팅방 메시지 목록 (커서 페이징, 최신순).
     *
     * @param viewerId 호출 사용자 — 비참여자면 차단
     * @param roomId   조회 대상 방
     * @param cursor   앞 페이지 마지막 메시지 id ({@code null} 이면 첫 페이지)
     * @param size     페이지 크기 (1~100 클램핑)
     */
    public MessageCursorPageResponse listMessages(Long viewerId, Long roomId, Long cursor, int size) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isParticipant(viewerId)) {
            throw new BusinessException(ErrorCode.NOT_CHAT_PARTICIPANT);
        }

        int safeSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);
        List<Message> rows = messageRepository.findByRoomCursor(
                roomId, cursor, PageRequest.of(0, safeSize + 1));

        boolean hasNext = rows.size() > safeSize;
        List<Message> page = hasNext ? rows.subList(0, safeSize) : rows;

        Long nextCursor = hasNext && !page.isEmpty()
                ? page.get(page.size() - 1).getId()
                : null;

        List<MessageResponse> items = page.stream().map(MessageResponse::from).toList();
        return new MessageCursorPageResponse(items, nextCursor, hasNext);
    }

    // ---------- 인증/인가 헬퍼 (WebSocket 컨트롤러용) ----------

    /**
     * 방 참여자 검증. WebSocket {@code @MessageMapping} 핸들러나 STOMP 인터셉터에서
     * 발화 직전 호출.
     *
     * @throws BusinessException {@link ErrorCode#CHAT_ROOM_NOT_FOUND}
     * @throws BusinessException {@link ErrorCode#NOT_CHAT_PARTICIPANT}
     */
    public void assertParticipant(Long userId, Long roomId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isParticipant(userId)) {
            throw new BusinessException(ErrorCode.NOT_CHAT_PARTICIPANT);
        }
    }
}
