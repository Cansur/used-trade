package com.portfolio.used_trade.chat.domain;

import com.portfolio.used_trade.common.domain.BaseEntity;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅 메시지 엔티티.
 *
 * <p><b>설계 원칙</b>
 * <ul>
 *   <li>setter 미노출 — 메시지는 불변. 수정/삭제 기능 없음.</li>
 *   <li>참여자 검증은 정적 팩토리 {@link #send} 가 책임 — 비참여자 송신 차단.</li>
 *   <li>입력 길이 검증 (빈 값, max 4000자) 은 서비스/DTO 레이어 책임.</li>
 * </ul>
 *
 * <p><b>관계</b>
 * <ul>
 *   <li>{@code chatRoom} : ChatRoom 단방향 ManyToOne (LAZY)</li>
 *   <li>{@code sender}   : User 단방향 ManyToOne (LAZY)</li>
 * </ul>
 *
 * <p><b>인덱스</b>
 * <ul>
 *   <li>{@code idx_messages_room_id} : (chat_room_id, id) — 방 내 메시지 커서 페이징
 *       ({@code WHERE chat_room_id=? ORDER BY id DESC LIMIT N}) 의 직접 지원 인덱스</li>
 * </ul>
 */
@Entity
@Table(
        name = "messages",
        indexes = {
                @Index(name = "idx_messages_room_id", columnList = "chat_room_id,id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    /** 본문. MySQL TEXT — 길이 제한 없이 저장하되 입력 검증은 서비스 레이어. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageType type;

    // ---------- 생성 ----------

    /**
     * 메시지 발화 정적 팩토리.
     *
     * <p>참여자 가드 — sender 가 chatRoom 의 buyer 도 product.seller 도 아니면
     * {@link ErrorCode#NOT_CHAT_PARTICIPANT}. 정적 팩토리에서 막는 이유:
     * 도메인 단에서 비참여자 발화를 원천 차단하면 서비스 레이어 어디서 호출해도 회귀 보장.
     */
    public static Message send(ChatRoom chatRoom, User sender, String content, MessageType type) {
        if (!chatRoom.isParticipant(sender.getId())) {
            throw new BusinessException(ErrorCode.NOT_CHAT_PARTICIPANT);
        }
        Message message = new Message();
        message.chatRoom = chatRoom;
        message.sender = sender;
        message.content = content;
        message.type = type;
        return message;
    }
}
