package com.portfolio.used_trade.payment.domain;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.trade.domain.Trade;
import com.portfolio.used_trade.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Payment} 도메인 단위 테스트.
 *
 * <p>회귀 박는 의도:
 * <ul>
 *   <li>initiate() 정상 — PENDING 으로 시작</li>
 *   <li>markPaid / markFailed / refund 의 상태 전이 가드</li>
 *   <li>이미 처리된 결제 재처리 시도 → PAYMENT_ALREADY_PROCESSED</li>
 * </ul>
 */
@DisplayName("Payment 도메인 단위 테스트")
class PaymentTest {

    private Trade trade;

    @BeforeEach
    void setUp() {
        User seller = User.create("seller@used-trade.com", "$2a$10$DUMMY", "판매자");
        ReflectionTestUtils.setField(seller, "id", 100L);
        User buyer = User.create("buyer@used-trade.com", "$2a$10$DUMMY", "구매자");
        ReflectionTestUtils.setField(buyer, "id", 200L);
        Category category = Category.create("전자기기", 1);
        ReflectionTestUtils.setField(category, "id", 1L);
        Product product = Product.create(seller, category, "아이폰 15", "박스 미개봉", 1_200_000L);
        ReflectionTestUtils.setField(product, "id", 10L);

        trade = Trade.reserve(product, buyer);
        ReflectionTestUtils.setField(trade, "id", 1000L);
    }

    @Nested
    @DisplayName("정적 팩토리 initiate()")
    class Factory {

        @Test
        @DisplayName("PENDING 상태로 생성, trade/amount 정확히 할당")
        void initiate_pendingState() {
            Payment p = Payment.initiate(trade, 1_200_000L);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(p.getTrade()).isSameAs(trade);
            assertThat(p.getAmount()).isEqualTo(1_200_000L);
            assertThat(p.getGatewayTxId()).isNull();
        }
    }

    @Nested
    @DisplayName("상태 전이 — 정상 경로")
    class TransitionsHappyPath {

        @Test
        @DisplayName("markPaid() — PENDING → PAID, gatewayTxId 채워짐")
        void markPaid() {
            Payment p = Payment.initiate(trade, 1_200_000L);
            p.markPaid("MOCKPG-TX-001");
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(p.getGatewayTxId()).isEqualTo("MOCKPG-TX-001");
        }

        @Test
        @DisplayName("markFailed() — PENDING → FAILED, gatewayTxId 는 null 유지")
        void markFailed() {
            Payment p = Payment.initiate(trade, 1_200_000L);
            p.markFailed();
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(p.getGatewayTxId()).isNull();
        }

        @Test
        @DisplayName("refund() — PAID → REFUNDED")
        void refund() {
            Payment p = Payment.initiate(trade, 1_200_000L);
            p.markPaid("MOCKPG-TX-001");
            p.refund();
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        }
    }

    @Nested
    @DisplayName("상태 전이 — 가드")
    class TransitionsGuards {

        @Test
        @DisplayName("PAID 인데 또 markPaid → PAYMENT_ALREADY_PROCESSED")
        void markPaid_alreadyPaid_throws() {
            Payment p = Payment.initiate(trade, 1_200_000L);
            p.markPaid("TX-1");
            assertThatThrownBy(() -> p.markPaid("TX-2"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        @Test
        @DisplayName("PAID 인데 markFailed → PAYMENT_ALREADY_PROCESSED")
        void markFailed_alreadyPaid_throws() {
            Payment p = Payment.initiate(trade, 1_200_000L);
            p.markPaid("TX-1");
            assertThatThrownBy(p::markFailed)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        @Test
        @DisplayName("PENDING 인 채로 refund → PAYMENT_ALREADY_PROCESSED (PAID 만 환불 가능)")
        void refund_whenPending_throws() {
            Payment p = Payment.initiate(trade, 1_200_000L);
            assertThatThrownBy(p::refund)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        @Test
        @DisplayName("FAILED 는 종착 — markPaid / refund 모두 거부")
        void failedTerminal() {
            Payment p = Payment.initiate(trade, 1_200_000L);
            p.markFailed();
            assertThatThrownBy(() -> p.markPaid("TX-1"))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(p::refund)
                    .isInstanceOf(BusinessException.class);
        }
    }
}
