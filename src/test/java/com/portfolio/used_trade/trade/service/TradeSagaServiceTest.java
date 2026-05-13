package com.portfolio.used_trade.trade.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.payment.domain.PaymentStatus;
import com.portfolio.used_trade.payment.dto.PaymentResponse;
import com.portfolio.used_trade.payment.service.PaymentService;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.trade.domain.Trade;
import com.portfolio.used_trade.trade.domain.TradeStatus;
import com.portfolio.used_trade.trade.dto.TradeConfirmResponse;
import com.portfolio.used_trade.trade.dto.TradeResponse;
import com.portfolio.used_trade.trade.repository.TradeRepository;
import com.portfolio.used_trade.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link TradeSagaService} 단위 테스트 — Saga 의 두 경로 + 가드.
 *
 * <p>회귀 박는 의도:
 * <ul>
 *   <li>PG 성공 → trade.confirm() 호출 → CONFIRMED 응답</li>
 *   <li>PG 실패 → trade.cancel() 보상 호출 → PAYMENT_FAILED throw</li>
 *   <li>buyer 불일치 → NOT_PRODUCT_OWNER, payment 호출 없음</li>
 *   <li>trade 부재 → TRADE_NOT_FOUND</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradeSagaService 단위 테스트")
class TradeSagaServiceTest {

    @Mock private TradeRepository tradeRepository;
    @Mock private TradeService tradeService;
    @Mock private PaymentService paymentService;

    @InjectMocks private TradeSagaService tradeSagaService;

    private User seller;
    private User buyer;
    private User stranger;
    private Trade trade;

    @BeforeEach
    void setUp() {
        seller = User.create("seller@used-trade.com", "$2a$10$DUMMY", "판매자");
        ReflectionTestUtils.setField(seller, "id", 100L);
        buyer = User.create("buyer@used-trade.com", "$2a$10$DUMMY", "구매자");
        ReflectionTestUtils.setField(buyer, "id", 200L);
        stranger = User.create("stranger@used-trade.com", "$2a$10$DUMMY", "외부인");
        ReflectionTestUtils.setField(stranger, "id", 999L);
        Category category = Category.create("전자기기", 1);
        ReflectionTestUtils.setField(category, "id", 1L);
        Product product = Product.create(seller, category, "아이폰 15", "박스 미개봉", 1_200_000L);
        ReflectionTestUtils.setField(product, "id", 10L);
        trade = Trade.reserve(product, buyer);
        ReflectionTestUtils.setField(trade, "id", 1000L);
    }

    @Nested
    @DisplayName("confirm() — Saga 흐름")
    class Confirm {

        @Test
        @DisplayName("PG PAID → trade.confirm() 호출, CONFIRMED + payment 응답")
        void pgPaid_callsConfirmAndReturns() {
            given(tradeRepository.findById(1000L)).willReturn(Optional.of(trade));
            PaymentResponse paid = new PaymentResponse(
                    777L, 1000L, 1_200_000L, PaymentStatus.PAID, "MOCK-TX-001", LocalDateTime.now());
            given(paymentService.charge(trade)).willReturn(paid);
            TradeResponse confirmed = new TradeResponse(
                    1000L, 10L, 200L, 100L, 1_200_000L, TradeStatus.CONFIRMED, LocalDateTime.now());
            given(tradeService.confirm(200L, 1000L)).willReturn(confirmed);

            TradeConfirmResponse response = tradeSagaService.confirm(200L, 1000L);

            assertThat(response.trade().status()).isEqualTo(TradeStatus.CONFIRMED);
            assertThat(response.payment().status()).isEqualTo(PaymentStatus.PAID);
            verify(tradeService, never()).cancel(anyLong(), anyLong());
        }

        @Test
        @DisplayName("PG FAILED → trade.cancel() 보상 호출 + PAYMENT_FAILED throw")
        void pgFailed_compensatesWithCancel() {
            given(tradeRepository.findById(1000L)).willReturn(Optional.of(trade));
            PaymentResponse failed = new PaymentResponse(
                    777L, 1000L, 1_200_000L, PaymentStatus.FAILED, null, LocalDateTime.now());
            given(paymentService.charge(trade)).willReturn(failed);

            assertThatThrownBy(() -> tradeSagaService.confirm(200L, 1000L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_FAILED);

            // 보상 호출 — trade.cancel() 1번
            verify(tradeService, times(1)).cancel(200L, 1000L);
            verify(tradeService, never()).confirm(anyLong(), anyLong());
        }

        @Test
        @DisplayName("buyer 불일치 → NOT_PRODUCT_OWNER, payment 호출 없음")
        void buyerMismatch_throwsBeforePayment() {
            given(tradeRepository.findById(1000L)).willReturn(Optional.of(trade));

            assertThatThrownBy(() -> tradeSagaService.confirm(999L, 1000L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_PRODUCT_OWNER);

            verify(paymentService, never()).charge(any());
            verify(tradeService, never()).confirm(anyLong(), anyLong());
        }

        @Test
        @DisplayName("trade 부재 → TRADE_NOT_FOUND, payment 호출 없음")
        void tradeMissing_throws() {
            given(tradeRepository.findById(1000L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> tradeSagaService.confirm(200L, 1000L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TRADE_NOT_FOUND);

            verify(paymentService, never()).charge(any());
        }

        @Test
        @DisplayName("PG FAILED + 보상도 실패 → 보상 예외는 삼키고 PAYMENT_FAILED 로 통일 throw")
        void compensationItselfFails_stillThrowsPaymentFailed() {
            given(tradeRepository.findById(1000L)).willReturn(Optional.of(trade));
            PaymentResponse failed = new PaymentResponse(
                    777L, 1000L, 1_200_000L, PaymentStatus.FAILED, null, LocalDateTime.now());
            given(paymentService.charge(trade)).willReturn(failed);
            // 보상 cancel 도 실패 시뮬레이션 — 예: 이미 다른 경로로 trade 가 CANCELED 상태
            given(tradeService.cancel(200L, 1000L))
                    .willThrow(new BusinessException(ErrorCode.INVALID_TRADE_TRANSITION));

            assertThatThrownBy(() -> tradeSagaService.confirm(200L, 1000L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_FAILED);
        }
    }
}
