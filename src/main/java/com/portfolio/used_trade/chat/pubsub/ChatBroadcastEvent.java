package com.portfolio.used_trade.chat.pubsub;

import com.portfolio.used_trade.chat.dto.MessageResponse;

/**
 * Redis Pub/Sub 으로 인스턴스 간에 릴레이되는 메시지 페이로드.
 *
 * <p>{@code roomId} 를 페이로드에 포함하는 이유: 단일 채널 ({@code chat.broadcast}) 로
 * 모든 방을 묶어도 구독자 측이 자기 인스턴스에서 어느 토픽 ({@code /topic/chat/rooms/{roomId}})
 * 으로 broadcast 할지 결정해야 하기 때문. 채널을 방별로 나누면 publisher 부담 ↑ 대비
 * subscriber 자원 ↑ — 1차에는 단일 채널이 단순.
 */
public record ChatBroadcastEvent(Long roomId, MessageResponse message) {
}
