package com.portfolio.used_trade.common.exception;

/**
 * 비즈니스 로직에서 발생하는 예상 가능한 예외의 최상위 타입.
 *
 * <p><b>설계 의도</b>
 * <ul>
 *   <li>모든 도메인 예외는 이 클래스를 상속 → {@link GlobalExceptionHandler}
 *       한 곳에서 잡아 표준 응답으로 변환</li>
 *   <li>{@link RuntimeException} 상속 → 메서드 시그니처에 {@code throws}
 *       선언 불필요 (Spring 관례)</li>
 *   <li>{@link ErrorCode} 를 필수로 담아 HTTP 상태 + 응답 코드 결정을
 *       호출부가 아니라 예외가 스스로 알도록 설계</li>
 * </ul>
 *
 * <p><b>사용 예</b>
 * <pre>
 * if (userRepository.findById(id).isEmpty()) {
 *     throw new BusinessException(ErrorCode.USER_NOT_FOUND);
 * }
 * </pre>
 *
 * <p>컨텍스트별 메시지를 덮어쓰고 싶으면 {@link #BusinessException(ErrorCode, String)} 사용.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
