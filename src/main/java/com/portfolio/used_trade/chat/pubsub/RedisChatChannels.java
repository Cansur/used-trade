package com.portfolio.used_trade.chat.pubsub;

/**
 * Redis Pub/Sub 채널 상수.
 *
 * <p>1차 PR 은 모든 채팅 메시지를 단일 채널 {@link #CHAT_BROADCAST} 로 묶는다.
 * 페이로드에 {@code roomId} 가 포함되어 subscriber 가 적절한 토픽으로 분기 broadcast 한다.
 * 방별 채널 분리는 트래픽이 충분히 늘어 채널 fan-out 비용이 의미있어질 때 분리.
 */
public final class RedisChatChannels {

    public static final String CHAT_BROADCAST = "chat.broadcast";

    private RedisChatChannels() {
    }
}
