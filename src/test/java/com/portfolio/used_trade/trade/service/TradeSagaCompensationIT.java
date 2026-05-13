package com.portfolio.used_trade.trade.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.payment.domain.Payment;
import com.portfolio.used_trade.payment.domain.PaymentStatus;
import com.portfolio.used_trade.payment.gateway.PaymentGatewayPort;
import com.portfolio.used_trade.payment.repository.PaymentRepository;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Saga 보상 통합 테스트 — 실제 DB + 실제 도메인 메서드로 Pub/Sub Mock 게이트웨이 강제 실패 시
 * trade.cancel() 보상이 호출되어 Product 가 AVAILABLE 로 복원됨을 입증.
 *
 * <p><b>핵심 검증</b>
 * <ul>
 *   <li>PG 실패 → BusinessException(PAYMENT_FAILED) throw</li>
 *   <li>Trade 상태: RESERVED → CANCELED (보상)</li>
 *   <li>Product 상태: TRADING → AVAILABLE (보상)</li>
 *   <li>Payment row 영속: status=FAILED (감사/추적 근거)</li>
 * </ul>
 *
 * <p>{@link ControllablePaymentGateway} — 다음 charge 결과를 외부에서 제어. {@link Primary}
 * 로 등록되어 운영용 {@code MockPaymentGateway} 를 가린다.
 */
@SpringBootTest
@DisplayName("Trade Saga 보상 통합 테스트 (PG 실패 → cancel 보상)")
class TradeSagaCompensationIT {

    @Autowired private TradeSagaService tradeSagaService;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ControllablePaymentGateway controllableGateway;

    private User seller;
    private User buyer;
    private Product product;
    private Trade trade;

    @BeforeEach
    void setUp() {
        controllableGateway.reset();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String hash = passwordEncoder.encode("trade1234");

        seller = userRepository.save(
                User.create("seller-saga-" + unique + "@used-trade.com", hash, "sellerSG"));
        buyer = userRepository.save(
                User.create("buyer-saga-" + unique + "@used-trade.com", hash, "buyerSG"));
        Category category = categoryRepository.findAll().get(0);
        product = productRepository.save(
                Product.create(seller, category, "saga-target-" + unique, "Saga 보상 시연", 100_000L));

        // Trade.reserve() 가 product.reserve() (메모리 TRADING) 도 호출하므로
        // product 도 같은 시점에 다시 save 해서 DB 에 TRADING 반영해야 보상 단계가 정합.
        trade = Trade.reserve(product, buyer);
        productRepository.save(product);   // status=TRADING DB 반영
        trade = tradeRepository.save(trade);
    }

    @AfterEach
    void tearDown() {
        // payments → trades → product → users 순서 (FK 의존)
        paymentRepository.findByTradeId(trade.getId()).ifPresent(paymentRepository::delete);
        tradeRepository.deleteById(trade.getId());
        productRepository.deleteById(product.getId());
        userRepository.deleteById(buyer.getId());
        userRepository.deleteById(seller.getId());
    }

    @Test
    @DisplayName("PG 실패 → trade.cancel 보상 → Product AVAILABLE 복원 + Payment FAILED 영속 + PAYMENT_FAILED throw")
    void compensationRestoresProductAndPersistsFailedPayment() {
        controllableGateway.setNextResult(false);

        assertThatThrownBy(() -> tradeSagaService.confirm(buyer.getId(), trade.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_FAILED);

        // 1) Trade 는 CANCELED (보상)
        Trade reloadedTrade = tradeRepository.findById(trade.getId()).orElseThrow();
        assertThat(reloadedTrade.getStatus()).isEqualTo(TradeStatus.CANCELED);

        // 2) Product 는 AVAILABLE 로 복원 (cancel 도메인 메서드가 product.cancelReservation 호출)
        Product reloadedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloadedProduct.getStatus()).isEqualTo(ProductStatus.AVAILABLE);

        // 3) Payment row 는 FAILED 로 영속 (감사/추적 근거)
        Payment reloadedPayment = paymentRepository.findByTradeId(trade.getId()).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reloadedPayment.getGatewayTxId()).isNull();
    }

    @Test
    @DisplayName("PG 성공 → trade.confirm → Product 는 TRADING 유지, Payment PAID")
    void successPathConfirmsAndKeepsProductTrading() {
        controllableGateway.setNextResult(true);

        var response = tradeSagaService.confirm(buyer.getId(), trade.getId());

        assertThat(response.trade().status()).isEqualTo(TradeStatus.CONFIRMED);
        assertThat(response.payment().status()).isEqualTo(PaymentStatus.PAID);
        assertThat(response.payment().gatewayTxId()).isNotBlank();

        Product reloadedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloadedProduct.getStatus())
                .as("CONFIRMED 단계는 settle 까지 가지 않으므로 Product 는 TRADING 유지")
                .isEqualTo(ProductStatus.TRADING);
    }

    // ================================================================
    // 테스트 전용 게이트웨이 — @Primary 로 MockPaymentGateway 를 대체
    // ================================================================
    @TestConfiguration
    static class TestGatewayConfig {
        @Bean
        @Primary
        ControllablePaymentGateway controllablePaymentGateway() {
            return new ControllablePaymentGateway();
        }
    }

    /**
     * 다음 charge() 의 결과를 외부에서 제어하는 테스트용 게이트웨이.
     * 단일 호출 시나리오에 충분 — 동시 호출 시나리오는 별도.
     */
    static class ControllablePaymentGateway implements PaymentGatewayPort {
        private final AtomicBoolean nextSuccess = new AtomicBoolean(true);

        void setNextResult(boolean success) { nextSuccess.set(success); }
        void reset() { nextSuccess.set(true); }

        @Override
        public Result charge(Long tradeId, Long amountKrw) {
            return nextSuccess.get()
                    ? Result.success("CTRL-TX-" + tradeId)
                    : Result.failure("controllable_failure");
        }

        @Override
        public Result refund(String gatewayTxId) {
            return Result.success(gatewayTxId);
        }
    }
}
