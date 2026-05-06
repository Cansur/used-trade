package com.portfolio.used_trade.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 상품 등록 요청 DTO.
 *
 * <p>컨트롤러에서 {@code @Valid ProductRegisterRequest body} 로 받으면 위반 시
 * {@code MethodArgumentNotValidException} → {@code GlobalExceptionHandler} 가
 * {@code 400 INVALID_INPUT} 으로 변환.
 *
 * <p>가격 상한은 1억(100_000_000)원 — 거래 데모 범위. 운영 시 카테고리별
 * 상한이 다르면 별도 정책으로 분리.
 */
public record ProductRegisterRequest(

        @NotNull(message = "카테고리는 필수입니다.")
        @Positive(message = "카테고리 ID 는 양수여야 합니다.")
        Long categoryId,

        @NotBlank(message = "제목은 필수입니다.")
        @Size(min = 1, max = 100, message = "제목은 1~100자여야 합니다.")
        String title,

        @NotBlank(message = "본문은 필수입니다.")
        @Size(min = 1, max = 4000, message = "본문은 1~4000자여야 합니다.")
        String description,

        @NotNull(message = "가격은 필수입니다.")
        @PositiveOrZero(message = "가격은 0 이상이어야 합니다.")
        Long price
) {
}
