package com.portfolio.used_trade.product.dto;

import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.domain.ProductStatus;

import java.time.LocalDateTime;

/**
 * 상품 정보 응답 DTO.
 *
 * <p><b>의도적으로 포함한 결합 필드</b>
 * <ul>
 *   <li>{@code sellerNickname} — 클라이언트가 판매자 닉네임을 별도 호출 없이 표시</li>
 *   <li>{@code categoryName}   — 카테고리 라벨 즉시 노출</li>
 * </ul>
 *
 * <p>위 두 필드는 LAZY 연관을 한 번 더 끌어 쓰므로 반드시 트랜잭션 안에서
 * {@link #from(Product)} 를 호출해야 한다. {@code open-in-view: false} 환경에서
 * 컨트롤러가 직접 호출하면 {@code LazyInitializationException} 위험 — 그래서
 * Service 가 트랜잭션 종료 전 from 으로 변환해 반환한다.
 *
 * <p><b>의도적으로 빠진 필드</b>
 * <ul>
 *   <li>{@code version} — 낙관적 락 내부 메타. 클라이언트 노출 의미 없음</li>
 * </ul>
 */
public record ProductResponse(
        Long id,
        Long sellerId,
        String sellerNickname,
        Long categoryId,
        String categoryName,
        String title,
        String description,
        Long price,
        ProductStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /** Entity → DTO. 트랜잭션 컨텍스트 내부에서 호출할 것. */
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSeller().getId(),
                product.getSeller().getNickname(),
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getTitle(),
                product.getDescription(),
                product.getPrice(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
