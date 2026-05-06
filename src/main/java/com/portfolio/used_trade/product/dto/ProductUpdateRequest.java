package com.portfolio.used_trade.product.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 상품 부분 수정 요청 DTO (PATCH 의미).
 *
 * <p>모든 필드 {@code null 허용} — null 은 "변경하지 않음" 을 뜻한다. 한 필드만
 * 바꾸고 싶을 때 다른 필드를 보내지 않아도 됨.
 *
 * <p>값이 들어온 경우에만 길이/양수 등의 검증이 적용된다 — Bean Validation 의
 * non-null 어노테이션은 null 입력에 대해선 통과(skip)하는 게 표준 동작.
 *
 * <p>네 필드 모두 null 이면 서비스 레이어가 무동작 처리(또는 명시적 거부)하도록
 * 설계 — 본 단계에선 변경 없음으로 통과.
 */
public record ProductUpdateRequest(

        @Positive(message = "카테고리 ID 는 양수여야 합니다.")
        Long categoryId,

        @Size(min = 1, max = 100, message = "제목은 1~100자여야 합니다.")
        String title,

        @Size(min = 1, max = 4000, message = "본문은 1~4000자여야 합니다.")
        String description,

        @PositiveOrZero(message = "가격은 0 이상이어야 합니다.")
        Long price
) {
}
