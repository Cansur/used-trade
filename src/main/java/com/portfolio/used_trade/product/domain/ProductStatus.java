package com.portfolio.used_trade.product.domain;

/**
 * 상품 상태 머신.
 *
 * <pre>
 *     AVAILABLE ──reserve()──▶ TRADING ──markSold()──▶ SOLD
 *         ▲                       │
 *         └────cancelReservation()┘
 * </pre>
 *
 * <p>전이 규칙:
 * <ul>
 *   <li>{@link #AVAILABLE} : 판매 중. 예약/구매 가능 — 상품 목록의 기본 노출 대상.</li>
 *   <li>{@link #TRADING}   : 예약(거래 진행 중). 다른 사용자의 예약 시도는 거부.</li>
 *   <li>{@link #SOLD}      : 판매 완료. 종착 상태 — 더 이상 전이 없음.</li>
 * </ul>
 *
 * <p>전이 강제는 {@link Product} 의 도메인 메서드가 책임. 잘못된 호출은 BusinessException 으로 차단.
 *
 * <p>{@code @Enumerated(EnumType.STRING)} 강제 — ORDINAL 사용 시 enum 순서 변경이
 * 즉시 데이터 깨짐으로 이어진다.
 */
public enum ProductStatus {
    AVAILABLE,
    TRADING,
    SOLD
}
