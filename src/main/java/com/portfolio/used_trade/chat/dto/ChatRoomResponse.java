package com.portfolio.used_trade.chat.dto;

import com.portfolio.used_trade.chat.domain.ChatRoom;

import java.time.LocalDateTime;

/**
 * 채팅방 단건 응답.
 *
 * <p>Service 가 트랜잭션 내에서 LAZY 연관(product, buyer, seller)을 펼쳐 변환한다.
 */
public record ChatRoomResponse(
        Long id,
        Long productId,
        String productTitle,
        Long buyerId,
        String buyerNickname,
        Long sellerId,
        String sellerNickname,
        LocalDateTime createdAt
) {
    public static ChatRoomResponse from(ChatRoom room) {
        return new ChatRoomResponse(
                room.getId(),
                room.getProduct().getId(),
                room.getProduct().getTitle(),
                room.getBuyer().getId(),
                room.getBuyer().getNickname(),
                room.getProduct().getSeller().getId(),
                room.getProduct().getSeller().getNickname(),
                room.getCreatedAt()
        );
    }
}
