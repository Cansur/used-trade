package com.portfolio.used_trade.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 상품 이미지 업로드용 Presigned URL 발급 요청 DTO.
 *
 * <p><b>Presigned URL 흐름</b>
 * <pre>
 *   ① 클라 → 서버 : POST /api/products/{id}/images/presign  (이 DTO)
 *   ② 서버 → S3   : presign 요청 (s3:PutObject + TTL)
 *   ③ 서버 → 클라 : {@link PresignedUrlResponse} 반환
 *   ④ 클라 → S3   : 받은 uploadUrl 로 직접 PUT (서버를 거치지 않음 = 대역폭 절약)
 *   ⑤ 클라 → 서버 : objectKey 등록 (Product 의 imageUrls 업데이트, W2 에서 구현)
 * </pre>
 *
 * <p><b>왜 contentType 을 클라가 보내는가?</b>
 * S3 Presigned URL 은 발급 시점에 서명한 헤더와 PUT 시점의 헤더가 정확히 일치해야 통과한다.
 * Content-Type 은 일반적으로 서명 대상 — 클라가 의도한 타입을 미리 알려야 발급 시점에
 * 같은 값을 묶어 서명할 수 있다.
 *
 * <p><b>입력 검증</b>
 * <ul>
 *   <li>{@code filename}    — 1~255자, 공백 X. 확장자는 서비스가 추출해서 objectKey 에 보존</li>
 *   <li>{@code contentType} — image/* 만 허용 (악성 mime 차단)</li>
 * </ul>
 */
public record PresignedUrlRequest(

        @NotBlank(message = "파일명은 필수입니다.")
        @Size(min = 1, max = 255, message = "파일명은 1~255자여야 합니다.")
        String filename,

        @NotBlank(message = "contentType 은 필수입니다.")
        @Pattern(
                regexp = "^image/(jpeg|png|webp|gif)$",
                message = "이미지 mime 타입(jpeg/png/webp/gif) 만 허용됩니다."
        )
        String contentType
) {
}
