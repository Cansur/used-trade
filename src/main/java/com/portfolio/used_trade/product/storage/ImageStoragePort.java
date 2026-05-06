package com.portfolio.used_trade.product.storage;

import com.portfolio.used_trade.product.dto.PresignedUrlResponse;

/**
 * 이미지 저장소 추상화 (Hexagonal Architecture 의 Port).
 *
 * <p><b>왜 인터페이스로 두는가?</b>
 * 도메인/서비스 코드는 "Presigned URL 한 장 발급해" 만 알면 된다. 그게 AWS S3 인지,
 * GCS 인지, MinIO 인지, 로컬 Mock 인지는 도메인이 알 필요 없음. 운영체 변경이 어댑터
 * 교체 한 번으로 끝나도록 의존을 끊는다.
 *
 * <p><b>구현체</b>
 * <ul>
 *   <li>{@link MockImageStorage}   — 개발/테스트. 가짜 URL 반환 (실제 PUT 불가)</li>
 *   <li>{@code S3ImageStorage}     — W2 에서 추가 예정. AWS SDK 로 진짜 presign 호출</li>
 * </ul>
 *
 * <p>{@link org.springframework.context.annotation.Profile @Profile} 어노테이션으로
 * 환경별 단일 빈만 활성화 → ProductImageService 는 어떤 구현이 주입됐는지 모름.
 */
public interface ImageStoragePort {

    /**
     * 주어진 objectKey 에 대해 단발성 PUT 권한을 가진 Presigned URL 을 발급.
     *
     * @param objectKey   S3 버킷 내 절대 경로 (예: {@code products/123/uuid.jpg})
     * @param contentType 클라가 PUT 시 사용할 Content-Type (서명 대상에 포함)
     * @return uploadUrl + objectKey + 만료 초
     */
    PresignedUrlResponse presign(String objectKey, String contentType);
}
