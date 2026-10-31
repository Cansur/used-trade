package com.portfolio.used_trade.trade.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.domain.ProductStatus;
import com.portfolio.used_trade.product.service.ProductService;
import com.portfolio.used_trade.trade.domain.Trade;
import com.portfolio.used_trade.trade.domain.TradeStatus;
import com.portfolio.used_trade.trade.dto.TradeResponse;
import com.portfolio.used_trade.trade.repository.TradeRepository;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.service.UserService;
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


import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TradeService} 단위 테스트.
 *
 * <p>이번 PR 의 단위 검증 범위:
 * <ul>
 *   <li>reserve 정상 경로 — Trade 생성, Product TRADING 전이, 가격 스냅샷</li>
 *   <li>존재 검증 가드 — buyer / product</li>
 *   <li>도메인 가드 위임 — 본인 상품, 이미 TRADING 인 상품</li>
 * </ul>
 *
 * <p>이번 PR 의 단위 검증 범위 밖:
 * <ul>
 *   <li>{@code @Retryable} 의 실제 재시도 동작 — AOP 프록시가 단위 테스트에선 동작하지 않음.
 *       동시성 시뮬레이션은 별도 통합 테스트(다음 단계)로 검증.</li>
 *   <li>JPA flush / FK 제약 / 트랜잭션 — 통합 테스트 영역</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradeService 단위 테스트")
class TradeServiceTest {

    @Mock private UserService userService;
    @Mock private ProductService productService;
    @Mock private TradeRepository tradeRepository;

    @InjectMocks private TradeService tradeService;

    private User seller;
    private User buyer;
    private Category category;

    @BeforeEach
    void setUp() {
        seller = User.create("seller@used-trade.com", "$2a$10$DUMMY", "판매자");
        ReflectionTestUtils.setField(seller, "id", 100L);

        buyer = User.create("buyer@used-trade.com", "$2a$10$DUMMY", "구매자");
        ReflectionTestUtils.setField(buyer, "id", 200L);

        category = Category.create("전자기기", 1);
        ReflectionTestUtils.setField(category, "id", 1L);
    }

    private Product makeProduct(ProductStatus status) {
        Product p = Product.create(seller, category, "아이폰 15", "박스 미개봉", 1_200_000L);
        ReflectionTestUtils.setField(p, "id", 10L);
        if (status != ProductStatus.AVAILABLE) {
            ReflectionTestUtils.setField(p, "status", status);
        }
        return p;
    }

    @Nested
    @DisplayName("reserve()")
    class Reserve {

        @Test
        @DisplayName("정상: Trade 가 RESERVED 로 저장되고 상품 전이는 ProductService.reserve 에 위임된다")
        void reserve_success() {
            Product product = makeProduct(ProductStatus.AVAILABLE);
            given(userService.getUser(200L)).willReturn(buyer);
            given(productService.getProduct(10L)).willReturn(product);
            // saveAndFlush 는 인자로 받은 trade 에 id 만 부여해 그대로 반환한다고 가정 (영속화 단순화).
            given(tradeRepository.saveAndFlush(any(Trade.class))).willAnswer(inv -> {
                Trade t = inv.getArgument(0);
                ReflectionTestUtils.setField(t, "id", 1000L);
                return t;
            });

            TradeResponse response = tradeService.reserve(200L, 10L);

            ArgumentCaptor<Trade> captor = ArgumentCaptor.forClass(Trade.class);
            verify(tradeRepository).saveAndFlush(captor.capture());
            Trade saved = captor.getValue();

            assertThat(saved.getStatus()).isEqualTo(TradeStatus.RESERVED);
            assertThat(saved.getPricePaid()).isEqualTo(1_200_000L);
            assertThat(saved.getProduct()).isSameAs(product);
            assertThat(saved.getBuyer()).isSameAs(buyer);
            verify(productService).reserve(10L);   // 상품 상태 전이는 product 도메인이

            assertThat(response.id()).isEqualTo(1000L);
            assertThat(response.productId()).isEqualTo(10L);
            assertThat(response.buyerId()).isEqualTo(200L);
            assertThat(response.sellerId()).isEqualTo(100L);
            assertThat(response.pricePaid()).isEqualTo(1_200_000L);
            assertThat(response.status()).isEqualTo(TradeStatus.RESERVED);
        }

        @Test
        @DisplayName("Buyer 가 DB 에 없으면 USER_NOT_FOUND — 존재 가드는 Product 조회보다 먼저")
        void reserve_buyerNotFound_throws() {
            given(userService.getUser(200L)).willThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

            assertThatThrownBy(() -> tradeService.reserve(200L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);

            verify(productService, never()).getProduct(any());
            verify(tradeRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Product 가 DB 에 없으면 PRODUCT_NOT_FOUND")
        void reserve_productNotFound_throws() {
            given(userService.getUser(200L)).willReturn(buyer);
            given(productService.getProduct(10L)).willThrow(new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

            assertThatThrownBy(() -> tradeService.reserve(200L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

            verify(tradeRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("판매자 본인이 자기 상품을 예약하면 TRADE_SELF_NOT_ALLOWED — 도메인 가드 위임")
        void reserve_selfTrade_throws() {
            Product product = makeProduct(ProductStatus.AVAILABLE);
            given(userService.getUser(100L)).willReturn(seller);
            given(productService.getProduct(10L)).willReturn(product);

            assertThatThrownBy(() -> tradeService.reserve(100L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TRADE_SELF_NOT_ALLOWED);

            verify(productService, never()).reserve(any());   // 본인 상품이면 상품 상태를 건드리기 전에 차단
            verify(tradeRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("이미 TRADING 인 상품은 PRODUCT_NOT_AVAILABLE — ProductService.reserve 의 가드가 그대로 노출")
        void reserve_productAlreadyTrading_throws() {
            Product product = makeProduct(ProductStatus.TRADING);
            given(userService.getUser(200L)).willReturn(buyer);
            given(productService.getProduct(10L)).willReturn(product);
            willThrow(new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE)).given(productService).reserve(10L);

            assertThatThrownBy(() -> tradeService.reserve(200L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);

            verify(tradeRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("거래는 CANCELED, 상품 복원은 ProductService.cancelReservation 에 위임된다 (Saga 보상 경로)")
        void cancel_delegatesProductRestoreToProductService() {
            Product product = makeProduct(ProductStatus.TRADING);
            Trade trade = Trade.reserve(product, buyer);
            ReflectionTestUtils.setField(trade, "id", 1000L);
            given(tradeRepository.findById(1000L)).willReturn(Optional.of(trade));

            TradeResponse response = tradeService.cancel(200L, 1000L);

            assertThat(response.status()).isEqualTo(TradeStatus.CANCELED);
            verify(productService).cancelReservation(10L);
        }

        @Test
        @DisplayName("구매자가 아니면 NOT_PRODUCT_OWNER — 상품은 건드리지 않음")
        void cancel_notBuyer_throwsWithoutTouchingProduct() {
            Trade trade = Trade.reserve(makeProduct(ProductStatus.TRADING), buyer);
            ReflectionTestUtils.setField(trade, "id", 1000L);
            given(tradeRepository.findById(1000L)).willReturn(Optional.of(trade));

            assertThatThrownBy(() -> tradeService.cancel(999L, 1000L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_PRODUCT_OWNER);
            verify(productService, never()).cancelReservation(any());
        }
    }
}
