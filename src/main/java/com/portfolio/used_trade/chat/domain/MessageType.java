package com.portfolio.used_trade.chat.domain;

/**
 * 채팅 메시지 유형.
 *
 * <p>{@link #TEXT} 만 1차 PR 에서 사용. {@code SYSTEM_*} 종류는 향후 거래 라이프사이클
 * 알림 (예약 시작 / 취소 / 완료) 합류 시 점진 도입.
 *
 * <p>{@code @Enumerated(EnumType.STRING)} 강제 — ORDINAL 사용 시 enum 순서 변경이
 * 즉시 데이터 깨짐으로 이어진다.
 */
public enum MessageType {
    /** 사용자가 입력한 일반 텍스트 메시지. */
    TEXT
}
