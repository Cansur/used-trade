package com.portfolio.used_trade.product.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.domain.ProductStatus;
import com.portfolio.used_trade.product.dto.PresignedUrlRequest;
import com.portfolio.used_trade.product.dto.PresignedUrlResponse;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.product.storage.ImageStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 상품 이미지 업로드 워크플로 — 현재는 Presigned URL 발급만.
 *
 * <p><b>책임</b>
 * <ul>
 *   <li>본인 상품 검증 (다른 사람의 상품에 이미지 업로드 차단)</li>
 *   <li>SOLD 상태 거부 (거래 종료된 상품의 이미지 변경 차단)</li>
 *   <li>충돌 없는 objectKey 생성 ({@code products/{id}/{uuid}.{ext}})</li>
 *   <li>{@link ImageStoragePort} 호출 — 진짜 S3 인지 Mock 인지 모름</li>
 * </ul>
 *
 * <p><b>경로 결정</b>
 * 같은 파일명을 두 사용자가 동시에 올려도 충돌하지 않도록 UUID 를 끼운다.
 * 확장자는 보존 — 브라우저/CDN 의 mime 추론에 영향.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductImageService {

    private final ProductRepository productRepository;
    private final ImageStoragePort imageStorage;

    /**
     * 상품에 새 이미지를 업로드하기 위한 Presigned URL 발급.
     *
     * @throws BusinessException {@link ErrorCode#PRODUCT_NOT_FOUND}     상품 없음
     * @throws BusinessException {@link ErrorCode#NOT_PRODUCT_OWNER}     본인 아님
     * @throws BusinessException {@link ErrorCode#PRODUCT_NOT_AVAILABLE} SOLD 상태
     */
    public PresignedUrlResponse issueUploadUrl(Long sellerId, Long productId, PresignedUrlRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!product.isOwnedBy(sellerId)) {
            throw new BusinessException(ErrorCode.NOT_PRODUCT_OWNER);
        }
        if (product.getStatus() == ProductStatus.SOLD) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }

        String objectKey = buildObjectKey(productId, request.filename());
        return imageStorage.presign(objectKey, request.contentType());
    }

    /**
     * S3 objectKey 생성. UUID 로 충돌 방지하고 원본 확장자는 보존.
     *
     * <p>예: filename="iphone-front.jpg" → "products/123/3a906f67-...-2b6.jpg"
     */
    private String buildObjectKey(Long productId, String filename) {
        String ext = extractExtension(filename);
        String unique = UUID.randomUUID().toString();
        return "products/" + productId + "/" + unique + ext;
    }

    /**
     * 파일명 끝의 확장자 (마지막 점 포함). 점 없으면 빈 문자열.
     * 보안상 제어문자 제거는 mime 검증(DTO 의 @Pattern) 으로 흡수.
     */
    private static String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot).toLowerCase();
    }
}
