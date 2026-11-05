package com.portfolio.used_trade.trade.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.domain.ProductStatus;
import com.portfolio.used_trade.product.repository.CategoryRepository;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.product.service.ProductService;
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
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 거래와 상품 상태가 한 트랜잭션으로 묶이는지 실제 DB 로 검증 (ADR-1 에러 상황 3).
 *
 * <p>Mockito 단위 테스트에선 {@code @Transactional} 이 동작하지 않아 전파·롤백을 검증할 수 없다.
 */
@SpringBootTest
@DisplayName("거래-상품 트랜잭션 통합 테스트 (ADR-1)")
class TradeProductTransactionIT {

    @Autowired private TradeService tradeService;
    @Autowired private ProductService productService;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User seller;
    private User buyer;
    private Product product;
    private Trade trade;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String hash = passwordEncoder.encode("trade1234");

        seller = userRepository.save(
                User.create("seller-tx-" + unique + "@used-trade.com", hash, "sellerTx"));
        buyer = userRepository.save(
                User.create("buyer-tx-" + unique + "@used-trade.com", hash, "buyerTx"));
        Category category = categoryRepository.findAll().get(0);
        product = productRepository.save(
                Product.create(seller, category, "tx-target-" + unique, "트랜잭션 검증", 100_000L));
        // 상품은 AVAILABLE 그대로 둔다 — cancelReservation 이 실패하는 상황을 만들기 위함
        trade = tradeRepository.save(Trade.reserve(product, buyer));
    }

    @AfterEach
    void tearDown() {
        tradeRepository.deleteById(trade.getId());
        productRepository.deleteById(product.getId());
        userRepository.deleteById(buyer.getId());
        userRepository.deleteById(seller.getId());
    }

    @Test
    @DisplayName("상품 상태 전이를 트랜잭션 밖에서 부르면 거부되고 상태는 그대로다 (MANDATORY)")
    void transitionsOutsideTransaction_areRejected() {
        Long productId = product.getId();

        assertThatThrownBy(() -> productService.reserve(productId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> productService.markSold(productId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> productService.cancelReservation(productId))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(productRepository.findById(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.AVAILABLE);
    }

    @Test
    @DisplayName("상품 복원이 실패하면 거래 취소도 함께 롤백된다")
    void cancel_productRestoreFails_rollsBackTradeCancel() {
        assertThatThrownBy(() -> tradeService.cancel(buyer.getId(), trade.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);

        assertThat(tradeRepository.findById(trade.getId()).orElseThrow().getStatus())
                .isEqualTo(TradeStatus.RESERVED);
    }
}
