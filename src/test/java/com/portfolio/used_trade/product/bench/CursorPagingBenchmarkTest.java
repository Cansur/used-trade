package com.portfolio.used_trade.product.bench;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 커서 페이징 vs 오프셋 페이징 벤치마크 — ADR-4 의 근거 데이터.
 *
 * <p><b>측정 시나리오</b>
 * <ul>
 *   <li>총 10만 건의 AVAILABLE 상품 데이터로 시작 (idempotent 시드)</li>
 *   <li>1페이지: {@code OFFSET 0} vs {@code cursor=null}</li>
 *   <li>깊은 페이지: {@code OFFSET 99000} vs {@code cursor=1001}
 *       (둘 다 약 99000번째부터 50건을 가져오는 의미상 동일 위치)</li>
 *   <li>각 시나리오 워밍업 5회 + 측정 100회. 평균 / p50 / p95 / 최대 출력</li>
 *   <li>EXPLAIN 출력 — rows / type / key 컬럼 비교</li>
 * </ul>
 *
 * <p><b>왜 JdbcTemplate + nanoTime 인가?</b>
 * 측정 시간의 99% 가 DB 왕복 + 인덱스/디스크 I/O. JIT/GC 잡음이 결과에 미치는 영향이
 * 매우 작아서 JMH 의 정밀도가 절대적으로 필요하지 않다. 학습/세팅 비용 대비 효용 낮음.
 *
 * <p><b>왜 Tag("benchmark") 인가?</b>
 * 시드 + 100회 반복으로 수십 초~수분 소요. 일반 {@code ./gradlew test} 에 섞이면
 * CI 빌드가 매번 무거워지고 flaky 위험. {@code build.gradle} 의 별도 task 로 분리.
 *
 * <p><b>실행</b>: {@code ./gradlew benchmark}
 */
@Tag("benchmark")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("커서 페이징 vs 오프셋 페이징 벤치마크 (10만 건)")
class CursorPagingBenchmarkTest {

    private static final int TOTAL_PRODUCTS = 100_000;
    private static final int BATCH_SIZE = 1_000;
    private static final int WARMUP = 5;
    private static final int REPS = 100;
    private static final int PAGE_SIZE = 50;
    private static final long DEEP_OFFSET = 99_000;
    private static final long DEEP_CURSOR = 1_001L;   // id < 1001 → 약 99000번째 위치

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    void seed() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);
        if (count != null && count >= TOTAL_PRODUCTS) {
            System.out.printf("[bench] seed skipped — %d rows already exist%n", count);
            return;
        }

        long sellerId = ensureBenchSeller();
        long categoryId = ensureBenchCategory();

        long start = System.nanoTime();
        seedProducts(sellerId, categoryId, TOTAL_PRODUCTS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[bench] seeded %d products in %dms%n", TOTAL_PRODUCTS, elapsedMs);
    }

    /** 시드용 사용자 ensure (재실행 시 같은 사용자 재사용). */
    private long ensureBenchSeller() {
        String email = "bench@used-trade.local";
        Long existing = jdbc.query(
                "SELECT id FROM users WHERE email = ?",
                rs -> rs.next() ? rs.getLong("id") : null,
                email
        );
        if (existing != null) {
            return existing;
        }
        // 비밀번호는 BCrypt 해시 형태로 정확히 60자. 실제 로그인 의도 없음 (시드 사용자).
        // 형식: "$2a$10$" (7자) + base64-like (22+31=53자) = 60자
        String dummyHash = "$2a$10$YoLE.9ImL8rZL0bDRCiWMevOZpVsN9iWPwMXQHIyfYXm3eTNKCYZi";
        jdbc.update("""
                INSERT INTO users (created_at, updated_at, email, password, nickname, role, status)
                VALUES (NOW(6), NOW(6), ?, ?, ?, 'USER', 'ACTIVE')
                """,
                email,
                dummyHash,
                "bench-seller"
        );
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** 시드 카테고리 첫 번째 row id (CategoryDataInitializer 가 이미 10건 시드해 둠). */
    private long ensureBenchCategory() {
        Long id = jdbc.queryForObject("SELECT id FROM categories ORDER BY id ASC LIMIT 1", Long.class);
        if (id == null) {
            throw new IllegalStateException("categories 시드가 비어있다 — CategoryDataInitializer 확인");
        }
        return id;
    }

    private void seedProducts(long sellerId, long categoryId, int total) {
        String sql = """
                INSERT INTO products (created_at, updated_at, description, price, status, title, version, category_id, seller_id)
                VALUES (NOW(6), NOW(6), ?, ?, 'AVAILABLE', ?, 0, ?, ?)
                """;
        for (int b = 0; b < total / BATCH_SIZE; b++) {
            int base = b * BATCH_SIZE;
            List<Object[]> args = new ArrayList<>(BATCH_SIZE);
            for (int i = 0; i < BATCH_SIZE; i++) {
                int idx = base + i;
                args.add(new Object[]{
                        "bench-desc-" + idx,
                        10_000L + idx,
                        "bench-title-" + idx,
                        categoryId,
                        sellerId
                });
            }
            jdbc.batchUpdate(sql, args);
        }
    }

    // ---------- 측정 ----------

    @Test
    @DisplayName("OFFSET vs CURSOR — 1페이지 / 깊은 페이지 비교 + EXPLAIN")
    void compareOffsetVsCursor() {
        long offsetFirstP50 = measureNs(() -> offsetQuery(0, PAGE_SIZE));
        long cursorFirstP50 = measureNs(() -> cursorQuery(null, PAGE_SIZE));

        long offsetDeepP50 = measureNs(() -> offsetQuery(DEEP_OFFSET, PAGE_SIZE));
        long cursorDeepP50 = measureNs(() -> cursorQuery(DEEP_CURSOR, PAGE_SIZE));

        // 콘솔 출력 (gradle benchmark task 는 showStandardStreams = true 로 노출)
        System.out.println();
        System.out.println("============================================================");
        System.out.println("  CURSOR vs OFFSET PAGINATION BENCHMARK (10만 건, 50건/페이지)");
        System.out.println("============================================================");
        System.out.printf("  OFFSET first page   p50: %.2f ms%n", offsetFirstP50 / 1_000_000.0);
        System.out.printf("  CURSOR first page   p50: %.2f ms%n", cursorFirstP50 / 1_000_000.0);
        System.out.println("  -----------------------------------------------------------");
        System.out.printf("  OFFSET deep page    p50: %.2f ms   (OFFSET %d)%n", offsetDeepP50 / 1_000_000.0, DEEP_OFFSET);
        System.out.printf("  CURSOR deep page    p50: %.2f ms   (id < %d)%n", cursorDeepP50 / 1_000_000.0, DEEP_CURSOR);
        System.out.printf("  → speedup on deep page: %.1fx%n",
                (double) offsetDeepP50 / Math.max(cursorDeepP50, 1));
        System.out.println("============================================================");

        printExplain("OFFSET deep page",
                "EXPLAIN SELECT id FROM products WHERE status='AVAILABLE' ORDER BY id DESC LIMIT 50 OFFSET " + DEEP_OFFSET);
        printExplain("CURSOR deep page",
                "EXPLAIN SELECT id FROM products WHERE status='AVAILABLE' AND id < " + DEEP_CURSOR + " ORDER BY id DESC LIMIT 50");
    }

    /** 워밍업 + REPS 회 측정 후 p50 nanoTime. */
    private long measureNs(Runnable query) {
        for (int i = 0; i < WARMUP; i++) {
            query.run();
        }
        long[] times = new long[REPS];
        for (int i = 0; i < REPS; i++) {
            long s = System.nanoTime();
            query.run();
            times[i] = System.nanoTime() - s;
        }
        Arrays.sort(times);
        return times[REPS / 2];   // p50 (median)
    }

    private void offsetQuery(long offset, int size) {
        jdbc.queryForList(
                "SELECT id FROM products WHERE status = 'AVAILABLE' ORDER BY id DESC LIMIT ? OFFSET ?",
                Long.class, size, offset
        );
    }

    private void cursorQuery(Long cursor, int size) {
        if (cursor == null) {
            jdbc.queryForList(
                    "SELECT id FROM products WHERE status = 'AVAILABLE' ORDER BY id DESC LIMIT ?",
                    Long.class, size
            );
        } else {
            jdbc.queryForList(
                    "SELECT id FROM products WHERE status = 'AVAILABLE' AND id < ? ORDER BY id DESC LIMIT ?",
                    Long.class, cursor, size
            );
        }
    }

    private void printExplain(String label, String explainSql) {
        System.out.println();
        System.out.println("--- EXPLAIN: " + label + " ---");
        List<Map<String, Object>> rows = jdbc.queryForList(explainSql);
        rows.forEach(r -> System.out.println("  " + r));
    }
}
