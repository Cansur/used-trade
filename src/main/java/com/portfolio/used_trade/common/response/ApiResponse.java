package com.portfolio.used_trade.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 모든 REST API 의 표준 응답 포맷.
 *
 * <p>Java 17+ {@code record} 사용 — 불변(immutable) 데이터 캐리어로,
 * getter/equals/hashCode/toString 자동 생성. DTO 로 이상적.
 *
 * <p>성공 응답 예:
 * <pre>
 * {
 *   "success": true,
 *   "code": "OK",
 *   "message": null,
 *   "data": { ... },
 *   "timestamp": "2026-04-24T15:30:00"
 * }
 * </pre>
 *
 * <p>실패 응답 예:
 * <pre>
 * {
 *   "success": false,
 *   "code": "USER_NOT_FOUND",
 *   "message": "해당 사용자를 찾을 수 없습니다.",
 *   "data": null,
 *   "timestamp": "2026-04-24T15:30:00"
 * }
 * </pre>
 *
 * <p>{@link JsonInclude.Include#NON_NULL} — null 필드는 JSON 에서 제외하고 싶지만,
 * record 필드 단위로 어노테이션을 붙이긴 애매해서 application.yaml 의 Jackson
 * {@code default-property-inclusion: non_null} 설정으로 전역 처리했다.
 *
 * @param <T> 응답 data 타입
 */
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        LocalDateTime timestamp
) {

    /**
     * 성공 응답 (data 있음).
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "OK", null, data, LocalDateTime.now());
    }

    /**
     * 성공 응답 (data 없음). 주로 DELETE, 단순 ACK 응답용.
     *
     * <p>record 의 accessor 인 {@code success()} 와 시그니처 충돌 때문에
     * 이름을 {@code ok()} 로 구분한다. 의미는 동일.
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, "OK", null, null, LocalDateTime.now());
    }

    /**
     * 실패 응답. ErrorCode + 커스텀 메시지 기반.
     */
    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, code, message, null, LocalDateTime.now());
    }
}
