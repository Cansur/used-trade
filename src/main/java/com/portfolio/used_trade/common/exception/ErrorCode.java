package com.portfolio.used_trade.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 전역 에러 코드 카탈로그.
 *
 * <p>왜 enum 인가?
 * <ul>
 *   <li>타입 안전 — 오타로 존재하지 않는 코드를 쓸 수 없음</li>
 *   <li>한 곳에서 HTTP 상태 + 코드 문자열 + 기본 메시지를 묶어서 관리</li>
 *   <li>프론트는 {@code code} 문자열로 분기 (예: {@code USER_NOT_FOUND})</li>
 * </ul>
 *
 * <p>네이밍 규칙: {@code {DOMAIN}_{REASON}} — 예: {@code PRODUCT_NOT_FOUND}.
 *
 * <p>새 에러가 필요하면 도메인별로 섹션을 나눠 추가. 운영에서 고정되면
 * 코드명은 바꾸지 말 것 (프론트와의 계약).
 */
public enum ErrorCode {

    // ----- 공통 -----
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "잘못된 요청입니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "허용되지 않은 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."),

    // ----- 인증/인가 -----
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "EXPIRED_TOKEN", "만료된 토큰입니다."),

    // ----- 사용자 -----
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다."),
    // 미존재 이메일도 같은 코드를 반환 — 사용자 열거(user enumeration) 공격 방지.
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "INVALID_PASSWORD", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INACTIVE_USER(HttpStatus.FORBIDDEN, "INACTIVE_USER", "정지되었거나 탈퇴한 계정입니다."),

    // ----- 상품 -----
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."),
    PRODUCT_NOT_AVAILABLE(HttpStatus.CONFLICT, "PRODUCT_NOT_AVAILABLE", "판매 중인 상품이 아닙니다."),
    NOT_PRODUCT_OWNER(HttpStatus.FORBIDDEN, "NOT_PRODUCT_OWNER", "상품 소유자가 아닙니다."),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "카테고리를 찾을 수 없습니다."),

    // ----- 거래 (Saga / 낙관적 락 대상) -----
    TRADE_NOT_FOUND(HttpStatus.NOT_FOUND, "TRADE_NOT_FOUND", "거래 내역을 찾을 수 없습니다."),
    TRADE_ALREADY_RESERVED(HttpStatus.CONFLICT, "TRADE_ALREADY_RESERVED", "이미 예약된 상품입니다."),
    TRADE_SELF_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "TRADE_SELF_NOT_ALLOWED", "본인 상품은 예약할 수 없습니다."),
    INVALID_TRADE_TRANSITION(HttpStatus.CONFLICT, "INVALID_TRADE_TRANSITION", "현재 거래 상태에서 허용되지 않는 전이입니다."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "동시 수정이 감지되었습니다. 잠시 후 다시 시도해주세요."),

    // ----- 결제 -----
    PAYMENT_FAILED(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED", "결제에 실패했습니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "결제 내역을 찾을 수 없습니다."),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "PAYMENT_ALREADY_PROCESSED", "이미 처리된 결제입니다."),
    PAYMENT_REFUND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT_REFUND_FAILED", "환불에 실패했습니다."),

    // ----- 채팅 -----
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_ROOM_NOT_FOUND", "채팅방을 찾을 수 없습니다."),
    NOT_CHAT_PARTICIPANT(HttpStatus.FORBIDDEN, "NOT_CHAT_PARTICIPANT", "채팅방 참여자가 아닙니다."),
    CHAT_SELF_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "CHAT_SELF_NOT_ALLOWED", "본인 상품에 채팅을 시작할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
