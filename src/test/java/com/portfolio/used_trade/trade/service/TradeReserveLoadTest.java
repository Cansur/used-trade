package com.portfolio.used_trade.trade.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.repository.CategoryRepository;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.trade.repository.TradeRepository;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trade.reserve 의 N=50 동시 부하 시 응답 시간 분포 측정 — ADR-2 의 정량 데이터 확보.
 *
 * <p>{@link TradeReserveConcurrencyTest} 가 정확성 (보호 작동) 을 검증한다면, 본 테스트는
 * 그 보호의 비용을 수치로 박는다. p50 / p95 / max / wall-clock total 을 콘솔에 출력해
 * docs/adr/002-optimistic-locking.md 의 Before/After 표에 옮긴다.
 *
 * <p>HikariCP max-pool-size = 10 인 로컬 환경 기준이라 N 은 50 으로 고정. 그 이상은
 * connection 대기 시간이 측정값 자체를 지배해 의미 있는 수치가 안 나온다 — pool 크기를
 * 함께 늘려야 비로소 의미가 생기는 영역이라 별도 부하 테스트 (k6 등) 영역이다.
 *
 * <p>단언:
 * <ol>
 *   <li>정확히 1 건만 RESERVED — 정확성 회귀</li>
 *   <li>p95 가 합리적 범위 (느슨하게 5초 미만) — 회귀 가드. 실제 수치는 콘솔 출력으로 ADR 에 옮긴다</li>
 * </ol>
 */
@SpringBootTest
@DisplayName("Trade.reserve N=50 부하 측정 (ADR-2 After 정량)")
class TradeReserveLoadTest {

    private static final int CONCURRENT_BUYERS = 50;

    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private TradeService tradeService;
    @Autowired private PasswordEncoder passwordEncoder;

    private User seller;
    private List<User> buyers;
    private Product product;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String dummyHash = passwordEncoder.encode("trade1234");

        seller = userRepository.save(
                User.create("seller-load-" + unique + "@used-trade.com", dummyHash, "sellerL")
        );
        buyers = IntStream.range(0, CONCURRENT_BUYERS)
                .mapToObj(i -> userRepository.save(User.create(
                        "buyer-load-" + unique + "-" + i + "@used-trade.com",
                        dummyHash,
                        "buyerL" + i
                )))
                .toList();
        Category category = categoryRepository.findAll().get(0);
        product = productRepository.save(Product.create(
                seller, category, "load-target-" + unique, "ADR-2 After 정량 측정", 100_000L
        ));
    }

    @AfterEach
    void tearDown() {
        tradeRepository.findAll().stream()
                .filter(t -> t.getProduct().getId().equals(product.getId()))
                .forEach(tradeRepository::delete);
        productRepository.deleteById(product.getId());
        buyers.forEach(b -> userRepository.deleteById(b.getId()));
        userRepository.deleteById(seller.getId());
    }

    @Test
    @DisplayName("N=50 동시 reserve — 정확히 1건 RESERVED + 응답 시간 분포 측정")
    void reserve_loadProfile_N50() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_BUYERS);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Long> durationsMs = Collections.synchronizedList(new ArrayList<>());
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());

        long wallStart = System.currentTimeMillis();
        List<Future<?>> futures = new ArrayList<>();
        for (User buyer : buyers) {
            futures.add(executor.submit(() -> {
                try {
                    startGate.await();
                    long t0 = System.currentTimeMillis();
                    try {
                        tradeService.reserve(buyer.getId(), product.getId());
                        outcomes.add("OK");
                    } catch (BusinessException e) {
                        outcomes.add(e.getErrorCode().name());
                    } catch (Throwable t) {
                        outcomes.add("UNEXPECTED:" + t.getClass().getSimpleName());
                    }
                    durationsMs.add(System.currentTimeMillis() - t0);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        long wallTotalMs = System.currentTimeMillis() - wallStart;

        // ----- 정확성 회귀 -----
        long okCount = outcomes.stream().filter("OK"::equals).count();
        long unexpected = outcomes.stream().filter(s -> s.startsWith("UNEXPECTED")).count();
        assertThat(okCount).as("정확히 1건만 RESERVED").isEqualTo(1L);
        assertThat(unexpected).as("예상 외 예외 0건").isZero();

        long tradeRows = tradeRepository.findAll().stream()
                .filter(t -> t.getProduct().getId().equals(product.getId()))
                .count();
        assertThat(tradeRows).isEqualTo(1L);

        // ----- 응답 시간 분포 -----
        List<Long> sorted = new ArrayList<>(durationsMs);
        Collections.sort(sorted);
        long p50 = sorted.get((int) Math.ceil(sorted.size() * 0.50) - 1);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        long max = sorted.get(sorted.size() - 1);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);

        // 회귀 가드 — 비현실적으로 느려지면 fail
        assertThat(p95).as("p95 회귀 가드").isLessThan(5_000L);

        // ----- ADR-2 표에 옮길 수치 출력 -----
        long failureSum = outcomes.size() - okCount - unexpected;
        System.out.printf("""
                [trade.reserve N=%d After 측정]
                  wall-clock total : %d ms
                  per-call avg     : %.1f ms
                  per-call p50     : %d ms
                  per-call p95     : %d ms
                  per-call max     : %d ms
                  outcomes         : OK=%d, BusinessException=%d, unexpected=%d
                  trade rows in DB : %d
                %n""",
                CONCURRENT_BUYERS, wallTotalMs, avg, p50, p95, max,
                okCount, failureSum, unexpected, tradeRows);
    }
}
