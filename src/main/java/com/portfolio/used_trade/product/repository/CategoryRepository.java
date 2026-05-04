package com.portfolio.used_trade.product.repository;

import com.portfolio.used_trade.product.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 카테고리 영속성 어댑터.
 *
 * <p>Spring Data JPA 가 메서드 이름을 파싱해 자동 구현체를 만든다.
 *
 * <p>제공 메서드:
 * <ul>
 *   <li>{@link #findByName(String)} — 시드 idempotency / 운영 도구가 표시명으로 조회</li>
 *   <li>{@link #existsByName(String)} — 중복 시드 / 등록 중복 검사 (전체 row 안 가져오므로 빠름)</li>
 *   <li>{@link #findAllByActiveTrueOrderByDisplayOrderAsc()} —
 *       사용자에게 노출할 카테고리 목록 ({@code active=true} 만, 표시 순서대로)</li>
 * </ul>
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByName(String name);

    boolean existsByName(String name);

    List<Category> findAllByActiveTrueOrderByDisplayOrderAsc();
}
