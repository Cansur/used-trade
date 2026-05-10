package com.portfolio.used_trade.payment.domain;

/**
 * 결제 상태 머신.
 *
 * <pre>
 *     PENDING ──charge 성공──▶ PAID ──refund──▶ REFUNDED
 *        │
 *        └──charge 실패──▶ FAILED
 * </pre>
 *
 * <p>전이 규칙:
 * <ul>
 *   <li>{@link #PENDING}  : 결제 시도 직전 (Saga 진입 시 생성). 종착 X.</li>
 *   <li>{@link #PAID}     : Mock PG 응답 성공. trade.confirm() 호출 직전.</li>
 *   <li>{@link #FAILED}   : Mock PG 응답 실패. Saga 보상으로 trade.cancel() 트리거.</li>
 *   <li>{@link #REFUNDED} : PAID 이후 환불. CONFIRMED 거래 취소 흐름에서 사용 (다음 PR).</li>
 * </ul>
 *
 * <p>전이 강제는 {@link Payment} 도메인 메서드가 책임. 잘못된 호출은 BusinessException.
 *
 * <p>{@code @Enumerated(EnumType.STRING)} 강제 — ORDINAL 순서 변경 시 데이터 깨짐.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED
}
