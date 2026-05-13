package com.portfolio.used_trade.payment.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.payment.domain.Payment;
import com.portfolio.used_trade.payment.domain.PaymentStatus;
import com.portfolio.used_trade.payment.dto.PaymentResponse;
import com.portfolio.used_trade.payment.gateway.PaymentGatewayPort;
import com.portfolio.used_trade.payment.repository.PaymentRepository;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.trade.domain.Trade;
import com.portfolio.used_trade.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PaymentService} 단위 테스트.
 *
 * <p>회귀 박는 의도:
 * <ul>
 *   <li>PG 성공 → PAID + gatewayTxId 채워짐, 응답 status=PAID</li>
 *   <li>PG 실패 → FAILED 영속화 (트랜잭션 롤백 X) + 응답 status=FAILED, 예외 던지지 않음</li>
 *   <li>같은 trade 재결제 → PAYMENT_ALREADY_PROCESSED</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService 단위 테스트")
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentGatewayPort paymentGateway;

    @InjectMocks private PaymentService paymentService;

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
    @DisplayName("charge()")
    class Charge {

        @Test
        @DisplayName("PG 성공 → Payment PAID 영속, 응답 status=PAID + gatewayTxId 채움")
        void chargeSuccess() {
            given(paymentRepository.existsByTradeId(1000L)).willReturn(false);
            given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> {
                Payment p = inv.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 999L);
                return p;
            });
            given(paymentGateway.charge(1000L, 1_200_000L))
                    .willReturn(PaymentGatewayPort.Result.success("MOCK-TX-001"));

            PaymentResponse response = paymentService.charge(trade);

            assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
            assertThat(response.gatewayTxId()).isEqualTo("MOCK-TX-001");
            assertThat(response.amount()).isEqualTo(1_200_000L);

            // 저장된 entity 도 PAID
            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.PAID);
        }

        @Test
        @DisplayName("PG 실패 → Payment FAILED 영속, 응답 status=FAILED, 예외 던지지 않음 (Saga 가 분기)")
        void chargeFailure_returnsResultWithoutThrowing() {
            given(paymentRepository.existsByTradeId(1000L)).willReturn(false);
            given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> {
                Payment p = inv.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 999L);
                return p;
            });
            given(paymentGateway.charge(1000L, 1_200_000L))
                    .willReturn(PaymentGatewayPort.Result.failure("insufficient_funds"));

            PaymentResponse response = paymentService.charge(trade);

            assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(response.gatewayTxId()).isNull();
        }

        @Test
        @DisplayName("같은 trade 재결제 → PAYMENT_ALREADY_PROCESSED, PG 호출 안 함")
        void chargeDuplicate_throws() {
            given(paymentRepository.existsByTradeId(1000L)).willReturn(true);

            assertThatThrownBy(() -> paymentService.charge(trade))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);

            verify(paymentRepository, never()).save(any());
            verify(paymentGateway, never()).charge(anyLong(), anyLong());
        }
    }
}
