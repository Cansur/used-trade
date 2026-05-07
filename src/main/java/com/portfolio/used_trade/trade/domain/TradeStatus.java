package com.portfolio.used_trade.trade.domain;

/**
 * 거래 상태 머신.
 *
 * <pre>
 *     RESERVED ──confirm()──▶ CONFIRMED ──settle()──▶ SETTLED
 *         │
 *         └──cancel()──▶ CANCELED
 * </pre>
 *
 * <p>전이 규칙:
 * <ul>
 *   <li>{@link #RESERVED}  : 구매자가 거래 요청, 상품을 선점한 상태. Product 는 TRADING.</li>
 *   <li>{@link #CONFIRMED} : 판매자 수락 + 결제 인증 (다음 PR 에서 활성화).</li>
 *   <li>{@link #SETTLED}   : 인수 완료. 종착 상태. Product 는 SOLD.</li>
 *   <li>{@link #CANCELED}  : 취소. 종착 상태. RESERVED 단계에서만 허용 (이번 PR 범위).
 *       CONFIRMED 단계 취소는 환불 로직과 함께 다음 PR.</li>
 * </ul>
 *
 * <p>전이 강제는 {@link Trade} 의 도메인 메서드가 책임. 잘못된 호출은 BusinessException 으로 차단.
 *
 * <p>{@code @Enumerated(EnumType.STRING)} 강제 — ORDINAL 사용 시 enum 순서 변경이
 * 즉시 데이터 깨짐으로 이어진다.
 */
public enum TradeStatus {
    RESERVED,
    CONFIRMED,
    SETTLED,
    CANCELED
}
