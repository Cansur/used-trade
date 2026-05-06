package com.portfolio.used_trade.product.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.domain.ProductStatus;
import com.portfolio.used_trade.product.dto.PresignedUrlRequest;
import com.portfolio.used_trade.product.dto.PresignedUrlResponse;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.product.storage.ImageStoragePort;
import com.portfolio.used_trade.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ProductImageService} 단위 테스트.
 *
 * <p>의도: 권한/상태 가드, objectKey 생성 규칙(UUID + 확장자 보존), Storage 호출 위임을
 * Mockito 로 검증. 진짜 S3 / Mock 어댑터의 동작은 별도 영역.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductImageService 단위 테스트")
class ProductImageServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ImageStoragePort imageStorage;

    @InjectMocks private ProductImageService productImageService;

    private User seller;
    private Category category;

    @BeforeEach
    void setUp() {
        seller = User.create("seller@used-trade.com", "$2a$10$DUMMY", "판매자");
        ReflectionTestUtils.setField(seller, "id", 100L);

        category = Category.create("전자기기", 1);
        ReflectionTestUtils.setField(category, "id", 1L);
    }

    private Product makeProduct(ProductStatus status) {
        Product p = Product.create(seller, category, "iPhone 15", "sealed", 1_200_000L);
        ReflectionTestUtils.setField(p, "id", 10L);
        if (status != ProductStatus.AVAILABLE) {
            ReflectionTestUtils.setField(p, "status", status);
        }
        return p;
    }

    @Test
    @DisplayName("정상 — Storage.presign 호출 + objectKey 가 'products/{id}/{uuid}.{ext}' 형식")
    void issueUploadUrl_happy() {
        // ── Arrange ──
        Product product = makeProduct(ProductStatus.AVAILABLE);
        given(productRepository.findById(10L)).willReturn(Optional.of(product));
        given(imageStorage.presign(anyString(), anyString()))
                .willReturn(new PresignedUrlResponse("https://mock/url", "products/10/dummy.jpg", 300));

        var request = new PresignedUrlRequest("iphone-front.jpg", "image/jpeg");

        // ── Act ──
        PresignedUrlResponse response = productImageService.issueUploadUrl(100L, 10L, request);

        // ── Assert ──
        assertThat(response.uploadUrl()).isEqualTo("https://mock/url");
        assertThat(response.expiresInSeconds()).isEqualTo(300);

        // Storage 가 받은 objectKey 형식 검증 — 회귀 방지
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageStorage).presign(keyCaptor.capture(), typeCaptor.capture());

        // products/10/<UUID>.jpg
        assertThat(keyCaptor.getValue())
                .startsWith("products/10/")
                .endsWith(".jpg")
                .matches("products/10/[0-9a-f-]{36}\\.jpg");
        assertThat(typeCaptor.getValue()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("확장자 다른 파일명도 그대로 보존 (.png → .png)")
    void issueUploadUrl_preservesExtension() {
        Product product = makeProduct(ProductStatus.AVAILABLE);
        given(productRepository.findById(10L)).willReturn(Optional.of(product));
        given(imageStorage.presign(anyString(), anyString()))
                .willReturn(new PresignedUrlResponse("u", "k", 300));

        productImageService.issueUploadUrl(100L, 10L, new PresignedUrlRequest("photo.PNG", "image/png"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageStorage).presign(keyCaptor.capture(), anyString());
        assertThat(keyCaptor.getValue()).endsWith(".png");   // 대소문자 무관 정규화
    }

    @Test
    @DisplayName("상품 없음 — PRODUCT_NOT_FOUND, Storage 호출되지 않음")
    void issueUploadUrl_productNotFound() {
        given(productRepository.findById(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                productImageService.issueUploadUrl(100L, 404L,
                        new PresignedUrlRequest("a.jpg", "image/jpeg")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

        verify(imageStorage, never()).presign(any(), any());
    }

    @Test
    @DisplayName("타인이 호출 — NOT_PRODUCT_OWNER, Storage 호출되지 않음")
    void issueUploadUrl_notOwner() {
        given(productRepository.findById(10L)).willReturn(Optional.of(makeProduct(ProductStatus.AVAILABLE)));

        assertThatThrownBy(() ->
                productImageService.issueUploadUrl(999L, 10L,
                        new PresignedUrlRequest("a.jpg", "image/jpeg")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_PRODUCT_OWNER);

        verify(imageStorage, never()).presign(any(), any());
    }

    @Test
    @DisplayName("SOLD 상품 거부 — PRODUCT_NOT_AVAILABLE, Storage 호출되지 않음")
    void issueUploadUrl_soldRefused() {
        given(productRepository.findById(10L)).willReturn(Optional.of(makeProduct(ProductStatus.SOLD)));

        assertThatThrownBy(() ->
                productImageService.issueUploadUrl(100L, 10L,
                        new PresignedUrlRequest("a.jpg", "image/jpeg")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);

        verify(imageStorage, never()).presign(any(), any());
    }
}
