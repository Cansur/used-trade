package com.portfolio.used_trade.product.dto;

/**
 * Presigned URL 발급 응답 DTO.
 *
 * <p>응답을 받은 클라이언트는 {@link #uploadUrl} 로 PUT 요청하면 S3 에 직접 업로드된다.
 * 업로드 완료 후 {@link #objectKey} 를 서버에 다시 알려서 Product 와 연결한다 (W2).
 *
 * <p><b>각 필드 의미</b>
 * <ul>
 *   <li>{@code uploadUrl}         — 서명이 포함된 단발성 PUT URL. {@link #expiresInSeconds} 후 만료</li>
 *   <li>{@code objectKey}         — S3 버킷 내 객체 경로. 예: {@code products/123/uuid.jpg}.
 *                                   업로드 후 클라가 서버로 다시 보내야 함 (W2 의 등록 단계)</li>
 *   <li>{@code expiresInSeconds}  — uploadUrl 의 유효 시간 (초). TTL 짧을수록 안전</li>
 * </ul>
 *
 * <p><b>주의</b>: uploadUrl 은 서명이 포함된 단방향 토큰. 노출되면 그 시간 동안 누구나
 * 해당 objectKey 로 PUT 가능 → TTL 짧게(5분), 한 번 발급 후 즉시 사용 권장.
 */
public record PresignedUrlResponse(
        String uploadUrl,
        String objectKey,
        int expiresInSeconds
) {
}
