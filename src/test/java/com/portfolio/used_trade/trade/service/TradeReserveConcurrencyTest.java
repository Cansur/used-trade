package com.portfolio.used_trade.trade.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.domain.ProductStatus;
import com.portfolio.used_trade.product.repository.CategoryRepository;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.trade.domain.Trade;
import com.portfolio.used_trade.trade.domain.TradeStatus;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trade.reserve 동시성 통합 테스트 — <b>ADR-2 의 핵심 시연</b>.
 *
 * <p>이 테스트가 보장하는 것:
 * <ul>
 *   <li>같은 상품에 N 개의 동시 reserve 요청이 들어와도 정확히 <b>1건만 RESERVED</b> 로 성공</li>
 *   <li>나머지 N-1 건은 {@link ErrorCode#PRODUCT_NOT_AVAILABLE}
 *       또는 {@link ErrorCode#TRADE_ALREADY_RESERVED} 로 거부</li>
 *   <li>DB 의 trade row 도 정확히 1건 — 중복 거래 없음</li>
 *   <li>product 의 최종 상태는 {@link ProductStatus#TRADING}</li>
 *   <li>{@link BusinessException} 이 {@code @Recover} 의 wrap 없이 의도한 ErrorCode 로
 *       그대로 도달 — curl smoke 에서 발견한 회귀를 통합으로 박는다.</li>
 * </ul>
 *
 * <p>여기서 검증하는 메커니즘:
 * <ol>
 *   <li>Product 의 {@code @Version} — 동일 row 동시 update 시도 시 1건만 commit, 나머지는
 *       {@link org.springframework.dao.OptimisticLockingFailureException}</li>
 *   <li>{@code TradeService.reserve()} 의 {@code @Retryable + saveAndFlush} —
 *       메서드 안에서 충돌이 터지도록 강제하고 재시도</li>
 *   <li>재시도 시점에는 product status 가 이미 TRADING → 도메인 가드가
 *       {@link ErrorCode#PRODUCT_NOT_AVAILABLE} 로 즉시 거부 → {@code @Recover(BusinessException)}
 *       경로로 그대로 rethrow</li>
 * </ol>
 *
 * <p><b>왜 단위 테스트로 못 박는가?</b><br>
 * {@code @Retryable} 은 Spring AOP 프록시로 동작하는데, Mockito 단위 테스트에선 프록시가
 * 적용되지 않는다. {@code @SpringBootTest} 로 실제 컨텍스트를 띄워야 의미가 있다.
 *
 * <p><b>데이터 격리</b> — 본 테스트가 만든 user/product/trade 만 정확히 삭제.
 * {@code deleteAll()} 같은 광역 명령은 동일 DB 를 쓰는 다른 프로세스 (예: 로컬 bootRun)
 * 의 데이터까지 날릴 수 있어 사용 금지.
 */
@SpringBootTest
@DisplayName("Trade.reserve 동시성 통합 테스트 (ADR-2)")
class TradeReserveConcurrencyTest {

    private static final int CONCURRENT_BUYERS = 20;

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
                User.create("seller-conc-" + unique + "@used-trade.com", dummyHash, "sellerConc")
        );

        buyers = IntStream.range(0, CONCURRENT_BUYERS)
                .mapToObj(i -> userRepository.save(User.create(
                        "buyer-conc-" + unique + "-" + i + "@used-trade.com",
                        dummyHash,
                        "buyerConc" + i
                )))
                .toList();

        // CategoryDataInitializer 가 부트 시 채워둔 첫 카테고리 사용
        Category category = categoryRepository.findAll().get(0);

        product = productRepository.save(Product.create(
                seller, category, "concurrent-reserve-target-" + unique, "ADR-2 시연용", 100_000L
        ));
    }

    @AfterEach
    void tearDown() {
        // 본 테스트가 만든 trade/product/user 만 정확히 정리
        tradeRepository.findAll().stream()
                .filter(t -> t.getProduct().getId().equals(product.getId()))
                .forEach(tradeRepository::delete);
        productRepository.deleteById(product.getId());
        buyers.forEach(b -> userRepository.deleteById(b.getId()));
        userRepository.deleteById(seller.getId());
    }

    @Test
    @DisplayName("N=20 동시 reserve → 정확히 1건만 RESERVED, 나머지는 PRODUCT_NOT_AVAILABLE / TRADE_ALREADY_RESERVED")
    void concurrentReserve_onlyOneSucceeds() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_BUYERS);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        ConcurrentMap<ErrorCode, AtomicInteger> failuresByCode = new ConcurrentHashMap<>();
        ConcurrentMap<String, AtomicInteger> unexpectedByType = new ConcurrentHashMap<>();

        List<Future<?>> futures = new ArrayList<>();
        for (User buyer : buyers) {
            futures.add(executor.submit(() -> {
                try {
                    startGate.await();   // 모든 스레드가 동시 시작하도록 동기화
                    tradeService.reserve(buyer.getId(), product.getId());
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    failuresByCode.computeIfAbsent(e.getErrorCode(), k -> new AtomicInteger())
                            .incrementAndGet();
                } catch (Throwable t) {
                    // BusinessException 외 예외는 회귀 — 특히 ExhaustedRetryException.
                    // 진단성을 위해 root cause 도 키에 포함.
                    Throwable root = t;
                    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    String key = t.getClass().getSimpleName() + " <- " + root.getClass().getSimpleName();
                    unexpectedByType.computeIfAbsent(key, k -> new AtomicInteger())
                            .incrementAndGet();
                }
                return null;
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();
        boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(terminated).as("ExecutorService graceful shutdown").isTrue();

        // ----- 결과 검증 -----

        // 1) 정확히 1건만 성공
        assertThat(success.get())
                .as("정확히 1건만 RESERVED. 결과: success=%d, failures=%s, unexpected=%s",
                        success.get(), failuresByCode, unexpectedByType)
                .isEqualTo(1);

        // 2) 예상 외 예외 0건 — Spring Retry 의 ExhaustedRetryException 등이 도달하면 회귀
        assertThat(unexpectedByType)
                .as("BusinessException 외 예외가 도달하면 회귀 (특히 ExhaustedRetryException)")
                .isEmpty();

        // 3) 모든 호출은 success + BusinessException 으로 합산
        int failureSum = failuresByCode.values().stream().mapToInt(AtomicInteger::get).sum();
        assertThat(success.get() + failureSum).isEqualTo(CONCURRENT_BUYERS);

        // 4) 실패 ErrorCode 는 둘 중 하나만 — 다른 코드가 섞이면 의도 외 동작
        assertThat(failuresByCode.keySet())
                .as("실패는 PRODUCT_NOT_AVAILABLE 또는 TRADE_ALREADY_RESERVED 만 허용")
                .isSubsetOf(Set.of(
                        ErrorCode.PRODUCT_NOT_AVAILABLE,
                        ErrorCode.TRADE_ALREADY_RESERVED
                ));

        // 5) DB 측면 — trade row 정확히 1, product 최종 상태 TRADING
        long tradeRows = tradeRepository.findAll().stream()
                .filter(t -> t.getProduct().getId().equals(product.getId()))
                .count();
        assertThat(tradeRows).as("이 product 에 연결된 trade row 정확히 1").isEqualTo(1L);

        Trade winner = tradeRepository.findAll().stream()
                .filter(t -> t.getProduct().getId().equals(product.getId()))
                .findFirst().orElseThrow();
        assertThat(winner.getStatus()).isEqualTo(TradeStatus.RESERVED);
        assertThat(winner.getPricePaid()).isEqualTo(100_000L);   // 가격 스냅샷 회귀

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.TRADING);

        // ----- 시연용 출력 (./gradlew test --info 또는 benchmark task 시 콘솔에 노출) -----
        System.out.printf(
                "[concurrent-reserve N=%d] success=%d, failures=%s%n",
                CONCURRENT_BUYERS, success.get(), failuresByCode
        );
    }
}
