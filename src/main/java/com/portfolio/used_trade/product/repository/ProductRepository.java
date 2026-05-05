package com.portfolio.used_trade.product.repository;

import com.portfolio.used_trade.product.domain.Product;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 상품 영속성 어댑터.
 *
 * <p>제공:
 * <ul>
 *   <li>{@link JpaRepository} 의 기본 CRUD</li>
 *   <li>{@link #findAvailableByCursor} — AVAILABLE 상품의 커서 기반 페이징 조회</li>
 * </ul>
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * AVAILABLE 상품을 id 내림차순(최신순)으로 페이징 조회. 커서 기반.
     *
     * <p><b>왜 커서인가?</b> {@code OFFSET} 방식은 깊은 페이지에서 DB 가 버려질 행까지
     * 모두 스캔한다. 커서는 {@code WHERE id < :cursor} + 인덱스 시크 한 번 →
     * 페이지 깊이와 무관하게 일정 비용. 인덱스 {@code idx_products_status_id (status, id)}
     * 가 이 쿼리를 직접 지원한다.
     *
     * <p><b>왜 JOIN FETCH 인가?</b> {@link Product#getSeller()}, {@link Product#getCategory()}
     * 가 LAZY. 응답 DTO 변환에서 둘 다 접근 → 페이지 크기만큼 추가 SELECT 가 발생하는
     * N+1 을 차단하기 위해 한 번에 fetch.
     *
     * <p><b>cursor 의미</b>
     * <ul>
     *   <li>{@code null} — 첫 페이지 (가장 최신부터)</li>
     *   <li>그 외 — {@code id < cursor} 인 행만 (앞 페이지의 마지막 id)</li>
     * </ul>
     *
     * <p><b>호출자 약속</b>
     * Pageable 의 size 는 "페이지 크기 + 1" 로 호출할 것. 호출 측이 size+1 째 행의
     * 존재로 hasNext 를 판정한다.
     *
     * @param cursor     앞 페이지 마지막 상품 id ({@code null} 이면 첫 페이지)
     * @param categoryId 카테고리 필터 ({@code null} 이면 전체)
     * @param sellerId   판매자 필터 ({@code null} 이면 전체)
     * @param pageable   {@code PageRequest.of(0, size + 1)} 형태로 전달
     */
    @Query("""
            SELECT p FROM Product p
            JOIN FETCH p.seller
            JOIN FETCH p.category
            WHERE p.status = com.portfolio.used_trade.product.domain.ProductStatus.AVAILABLE
              AND (:cursor IS NULL OR p.id < :cursor)
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:sellerId IS NULL OR p.seller.id = :sellerId)
            ORDER BY p.id DESC
            """)
    List<Product> findAvailableByCursor(
            @Param("cursor") Long cursor,
            @Param("categoryId") Long categoryId,
            @Param("sellerId") Long sellerId,
            Pageable pageable
    );
}
