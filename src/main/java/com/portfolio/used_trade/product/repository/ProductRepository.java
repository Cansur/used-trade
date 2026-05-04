package com.portfolio.used_trade.product.repository;

import com.portfolio.used_trade.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 상품 영속성 어댑터.
 *
 * <p>현재는 {@link JpaRepository} 가 기본 제공하는 CRUD 만 사용.
 * 다음 단계 (ProductService TDD) 에서 검색/페이징/상태 필터 메서드를 추가한다 — 예:
 * <ul>
 *   <li>{@code findBySellerId(Long, Pageable)}</li>
 *   <li>{@code findByStatusOrderByIdDesc(ProductStatus, Pageable)} — 커서 페이징 후보</li>
 *   <li>{@code findByCategoryIdAndStatus(Long, ProductStatus, Pageable)}</li>
 * </ul>
 * 메서드는 도입 시점에 사용처와 함께 추가 — 미리 선언해 두지 않는다 (YAGNI).
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
}
