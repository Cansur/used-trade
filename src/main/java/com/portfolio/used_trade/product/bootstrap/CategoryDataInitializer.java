package com.portfolio.used_trade.product.bootstrap;

import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 부팅 시 카테고리 마스터 데이터를 시드.
 *
 * <p><b>왜 {@link CommandLineRunner} 인가?</b>
 * <ul>
 *   <li>{@code data.sql} 은 매번 무조건 실행 → INSERT IGNORE 같은 DB-방언에 의존</li>
 *   <li>코드 기반 시드는 {@code existsByName} 으로 명시적 idempotency — DB 방언 무관</li>
 *   <li>운영 변경 (이름 수정/순서 조정) 은 별도 운영 도구로 — 시드는 "최초 1회 보장" 만 책임</li>
 * </ul>
 *
 * <p><b>동작 보장</b>
 * <ul>
 *   <li>이미 같은 이름이 있으면 INSERT 하지 않음 (idempotent)</li>
 *   <li>운영자가 표시명/순서를 바꿨더라도 덮어쓰지 않음 — 이름 일치만 검사</li>
 *   <li>실행 순서: {@link Order @Order(0)} — 다른 도메인 시드보다 먼저 (Product FK 의존)</li>
 * </ul>
 */
@Component
@Order(0)
public class CategoryDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CategoryDataInitializer.class);

    /**
     * 중고거래 표준 카테고리 셋. 표시 순서는 1부터.
     * 운영 변경이 잦으면 enum 대신 별도 yml/json 으로 옮길 수 있음 — 현재 규모에선 코드로 충분.
     */
    private static final List<SeedCategory> SEED = List.of(
            new SeedCategory("전자기기", 1),
            new SeedCategory("의류/패션", 2),
            new SeedCategory("가구/인테리어", 3),
            new SeedCategory("도서/티켓/음반", 4),
            new SeedCategory("뷰티/미용", 5),
            new SeedCategory("스포츠/레저", 6),
            new SeedCategory("유아용품", 7),
            new SeedCategory("생활/주방", 8),
            new SeedCategory("반려동물용품", 9),
            new SeedCategory("기타", 10)
    );

    private final CategoryRepository categoryRepository;

    public CategoryDataInitializer(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        int inserted = 0;
        for (SeedCategory seed : SEED) {
            if (categoryRepository.existsByName(seed.name())) {
                continue;
            }
            categoryRepository.save(Category.create(seed.name(), seed.displayOrder()));
            inserted++;
        }
        if (inserted > 0) {
            log.info("[CategoryDataInitializer] 신규 시드 {}건 입력 (총 마스터 {}건)", inserted, SEED.size());
        } else {
            log.debug("[CategoryDataInitializer] 시드 변경 없음 — 기존 마스터 유지");
        }
    }

    /** 코드 내 시드 정의용 record — 외부 노출 X. */
    private record SeedCategory(String name, int displayOrder) {
    }
}
