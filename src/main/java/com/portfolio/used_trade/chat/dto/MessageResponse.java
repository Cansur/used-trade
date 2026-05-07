package com.portfolio.used_trade.chat.dto;

import com.portfolio.used_trade.chat.domain.Message;
import com.portfolio.used_trade.chat.domain.MessageType;

import java.time.LocalDateTime;

/**
 * 메시지 단건 응답.
 *
 * <p>roomId 는 STOMP 브로드캐스트 시 클라이언트가 자기 방 메시지인지 식별하는 데 쓰이고,
 * REST 페이징 응답에서는 호출자가 이미 roomId 를 알기에 의미가 적다 (그래도 일관성 위해 포함).
 */
public record MessageResponse(
        Long id,
        Long roomId,
        Long senderId,
        String senderNickname,
        String content,
        MessageType type,
        LocalDateTime createdAt
) {
    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getChatRoom().getId(),
                message.getSender().getId(),
                message.getSender().getNickname(),
                message.getContent(),
                message.getType(),
                message.getCreatedAt()
        );
    }
}
