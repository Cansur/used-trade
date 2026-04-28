package com.portfolio.used_trade.user.domain;

/**
 * 사용자 계정 상태.
 *
 * <p><b>왜 status 컬럼이 필요한가</b>
 * <ul>
 *   <li>탈퇴해도 DB row 를 즉시 삭제하면 외래키(상품/거래/채팅 기록) 가 깨진다.
 *       → soft delete: 행은 남기고 {@code DELETED} 로 표시</li>
 *   <li>운영 정책 위반자는 {@code SUSPENDED} 로 분리해 로그인만 차단</li>
 * </ul>
 *
 * <p><b>각 상태에서 허용되는 동작 (의도)</b>
 * <ul>
 *   <li>{@code ACTIVE}    : 모든 기능 사용 가능</li>
 *   <li>{@code SUSPENDED} : 로그인 불가, 데이터는 유지</li>
 *   <li>{@code DELETED}   : 로그인 불가, 닉네임은 "(탈퇴한 사용자)" 로 노출</li>
 * </ul>
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    DELETED
}
