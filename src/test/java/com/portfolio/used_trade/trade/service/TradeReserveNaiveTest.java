package com.portfolio.used_trade.trade.service;

import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.repository.CategoryRepository;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.trade.domain.Trade;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>ADR-2 의 Before 측 입증</b> — 동시성 보호가 없을 때 같은 상품에 중복 거래가 발생함을
 * 코드와 DB 로 직접 확인한다.
 *
 * <p>{@link TradeServiceNaive#reserveNaive} 는 {@code @Version} 검사도, retry 도, conditional
 * update 도 없는 의도적으로 단순한 구현이다. N=20 동시 호출 시 trades 테이블에 같은 product
 * 에 대한 row 가 1건 초과로 쌓이면 ADR-2 가 막으려는 결함이 실재함을 의미한다.
 *
 * <p>After 측 ({@link TradeReserveConcurrencyTest}) 과 동일한 셋업/N 으로 비교 가능하게
 * 작성. After 는 1건만, 여기 Before 는 보통 ≥ 2건.
 *
 * <p>이 결함의 빈도는 환경과 타이밍에 따라 달라질 수 있어 "정확히 N건" 이 아닌
 * "최소 2건 이상" 으로 검증한다 — 시연 가치는 "보호 있을 때 0 vs 보호 없을 때 ≥ 2" 의 대비.
 */
@SpringBootTest
@DisplayName("Trade.reserveNaive 동시성 결함 입증 (ADR-2 Before)")
class TradeReserveNaiveTest {

    private static final int CONCURRENT_BUYERS = 20;

    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private TradeServiceNaive tradeServiceNaive;
    @Autowired private PasswordEncoder passwordEncoder;

    private User seller;
    private List<User> buyers;
    private Product product;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String dummyHash = passwordEncoder.encode("trade1234");

        seller = userRepository.save(
                User.create("seller-naive-" + unique + "@used-trade.com", dummyHash, "sellerN")
        );
        buyers = IntStream.range(0, CONCURRENT_BUYERS)
                .mapToObj(i -> userRepository.save(User.create(
                        "buyer-naive-" + unique + "-" + i + "@used-trade.com",
                        dummyHash,
                        "buyerN" + i
                )))
                .toList();
        Category category = categoryRepository.findAll().get(0);
        product = productRepository.save(Product.create(
                seller, category, "naive-target-" + unique, "ADR-2 Before 시연", 100_000L
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
    @DisplayName("보호 없는 reserveNaive 는 N=20 동시 호출 시 trades 가 2건 이상 생긴다 (lost update + 중복 INSERT)")
    void naiveReserve_producesDuplicateTrades() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_BUYERS);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger callsAttempted = new AtomicInteger();
        AtomicInteger callsSucceeded = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (User buyer : buyers) {
            futures.add(executor.submit(() -> {
                try {
                    startGate.await();
                    callsAttempted.incrementAndGet();
                    tradeServiceNaive.reserveNaive(buyer.getId(), product.getId());
                    callsSucceeded.incrementAndGet();
                } catch (Throwable ignored) {
                    // Naive 시연이라 일부 실패는 자연스러움 (트랜잭션 롤백 등).
                    // 핵심은 succeeded 가 1보다 많을 때 trades row 가 1보다 많다는 점.
                }
                return null;
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long tradeRows = tradeRepository.findAll().stream()
                .filter(t -> t.getProduct().getId().equals(product.getId()))
                .count();

        // ----- 결함 입증 -----
        // 보호 있는 After 통합 테스트는 정확히 1을 기대.
        // 보호 없는 Naive 는 ≥ 2 로 lost update + 중복 INSERT 를 입증.
        assertThat(tradeRows)
                .as("Naive 는 같은 product 에 대해 중복 거래를 만든다 — succeeded=%d", callsSucceeded.get())
                .isGreaterThanOrEqualTo(2L);

        // 시연용 출력
        System.out.printf(
                "[naive-reserve N=%d] attempts=%d, succeeded=%d, duplicate-trade-rows=%d%n",
                CONCURRENT_BUYERS, callsAttempted.get(), callsSucceeded.get(), tradeRows
        );
    }
}
