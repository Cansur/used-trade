package com.portfolio.used_trade.product.controller;

import com.portfolio.used_trade.common.response.ApiResponse;
import com.portfolio.used_trade.product.dto.PresignedUrlRequest;
import com.portfolio.used_trade.product.dto.PresignedUrlResponse;
import com.portfolio.used_trade.product.service.ProductImageService;
import com.portfolio.used_trade.user.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 이미지 관련 REST API.
 *
 * <p>현재 노출:
 * <ul>
 *   <li>POST /api/products/{productId}/images/presign — Presigned URL 발급 (인증 + 소유자)</li>
 * </ul>
 *
 * <p>이후 W2 에서 추가 예정:
 * <ul>
 *   <li>POST /api/products/{productId}/images — 업로드 완료 알림 + Product 와 연결</li>
 *   <li>DELETE /api/products/{productId}/images/{imageId} — 이미지 삭제</li>
 * </ul>
 *
 * <p>Security: SecurityConfig 의 {@code anyRequest().authenticated()} 정책에 자연스럽게
 * 잡힌다 (GET 만 permitAll). 별도 명시 불필요.
 */
@RestController
@RequestMapping("/api/products/{productId}/images")
@RequiredArgsConstructor
public class ProductImageController {

    private final ProductImageService productImageService;

    /**
     * Presigned URL 발급. 인증된 본인 상품에 한해 5분 유효한 단발성 PUT URL 을 반환.
     */
    @PostMapping("/presign")
    public ApiResponse<PresignedUrlResponse> issuePresignedUrl(
            @AuthenticationPrincipal AuthUser auth,
            @PathVariable("productId") Long productId,
            @Valid @RequestBody PresignedUrlRequest request
    ) {
        return ApiResponse.success(productImageService.issueUploadUrl(auth.id(), productId, request));
    }
}
