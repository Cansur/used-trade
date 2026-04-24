package com.portfolio.used_trade.common.exception;

import com.portfolio.used_trade.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 전역 예외 핸들러.
 *
 * <p>{@link RestControllerAdvice} — 모든 {@code @RestController} 에 공통 적용.
 * 각 컨트롤러에서 try-catch 반복을 제거하고, 응답 포맷을 한 곳에서 표준화.
 *
 * <p><b>처리 원칙</b>
 * <ul>
 *   <li>예상된 예외 ({@link BusinessException}) → WARN 로그 + ErrorCode 기반 응답</li>
 *   <li>Validation / 잘못된 요청 → 400 + 필드별 에러 메시지</li>
 *   <li>예상치 못한 예외 ({@link Exception}) → ERROR 로그 + 500 +
 *       스택트레이스는 응답에 절대 노출 금지</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 비즈니스 예외 — 가장 자주 발생.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode ec = e.getErrorCode();
        log.warn("[BusinessException] code={}, message={}", ec.getCode(), e.getMessage());
        return ResponseEntity
                .status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), e.getMessage()));
    }

    /**
     * {@code @Valid} 실패 — request body 필드 검증 실패.
     * 필드별 메시지를 ", " 로 합쳐 사람이 읽을 수 있게 반환.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> "%s: %s".formatted(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.joining(", "));
        ErrorCode ec = ErrorCode.INVALID_INPUT;
        log.warn("[ValidationException] {}", message);
        return ResponseEntity
                .status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), message));
    }

    /**
     * Request body JSON 파싱 실패.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        ErrorCode ec = ErrorCode.INVALID_INPUT;
        log.warn("[NotReadable] {}", e.getMessage());
        return ResponseEntity
                .status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), "요청 본문을 파싱할 수 없습니다."));
    }

    /**
     * 지원하지 않는 HTTP 메서드 (POST 엔드포인트에 GET 으로 요청 등).
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        ErrorCode ec = ErrorCode.METHOD_NOT_ALLOWED;
        log.warn("[MethodNotAllowed] {}", e.getMessage());
        return ResponseEntity
                .status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), e.getMessage()));
    }

    /**
     * 존재하지 않는 경로. Spring Boot 기본값에서는 발생 안 하는 경우가 있어
     * {@code spring.mvc.throw-exception-if-no-handler-found=true} 가 필요할 수 있음.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoHandlerFoundException e) {
        log.warn("[NoHandler] {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", "요청한 경로를 찾을 수 없습니다."));
    }

    /**
     * 최후 방어선. 예측하지 못한 모든 예외는 여기서 500 으로 잡힘.
     * 스택트레이스는 로그로만, 응답에는 일반 메시지만.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("[UnexpectedException]", e);
        ErrorCode ec = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity
                .status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getDefaultMessage()));
    }
}
