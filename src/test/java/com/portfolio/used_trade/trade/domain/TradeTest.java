package com.portfolio.used_trade.trade.domain;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.domain.ProductStatus;
import com.portfolio.used_trade.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Trade} 도메인 단위 테스트.
 *
 * <p>의도: 정적 팩토리에서 Product 의 상태 전이까지 함께 일어나는지, 상태 전이 가드,
 * 가격 스냅샷이 거래 시점 가격으로 고정되는지를 회귀 테스트로 박는다.
 *
 * <p>여기서 검증하지 않는 것:
 * <ul>
 *   <li>낙관적 락 충돌(@Version) — 동시성 통합 테스트 영역</li>
 *   <li>JPA 영속화 / FK 제약 — 통합 테스트 영역</li>
 * </ul>
 */
@DisplayName("Trade 도메인 단위 테스트")
class TradeTest {

    private User seller;
    private User buyer;
    private Category category;
    private Product product;

    @BeforeEach
    void setUp() {
        seller = User.create("seller@used-trade.com", "$2a$10$DUMMYHASH", "판매자");
        ReflectionTestUtils.setField(seller, "id", 100L);

        buyer = User.create("buyer@used-trade.com", "$2a$10$DUMMYHASH", "구매자");
        ReflectionTestUtils.setField(buyer, "id", 200L);

        category = Category.create("전자기기", 1);
        ReflectionTestUtils.setField(category, "id", 1L);

        product = Product.create(seller, category, "아이폰 15", "박스 미개봉", 1_200_000L);
        ReflectionTestUtils.setField(product, "id", 10L);
    }

    @Nested
    @DisplayName("정적 팩토리 reserve()")
    class Factory {

        @Test
        @DisplayName("거래 생성 직후 상태는 RESERVED, Product 는 TRADING 으로 전이된다")
        void reserve_initialState() {
            Trade trade = Trade.reserve(product, buyer);

            assertThat(trade.getStatus()).isEqualTo(TradeStatus.RESERVED);
            assertThat(trade.getProduct()).isSameAs(product);
            assertThat(trade.getBuyer()).isSameAs(buyer);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.TRADING);
        }

        @Test
        @DisplayName("pricePaid 는 거래 시점 Product.price 를 스냅샷으로 보관 — 사후 가격 변경에 영향 없음")
        void reserve_capturesPricedAtCreation() {
            Trade trade = Trade.reserve(product, buyer);

            // 거래 후 상품 가격이 바뀌어도 거래 금액은 1_200_000 으로 고정
            product.changePrice(2_000_000L);

            assertThat(trade.getPricePaid()).isEqualTo(1_200_000L);
        }

        @Test
        @DisplayName("이미 TRADING 인 상품은 예약 불가 — Product.reserve() 의 가드가 그대로 노출")
        void reserve_alreadyTradingProduct_throws() {
            product.reserve();   // AVAILABLE → TRADING (앞선 다른 거래로 선점됨)

            assertThatThrownBy(() -> Trade.reserve(product, buyer))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("판매자 본인은 자기 상품을 예약할 수 없다")
        void reserve_sellerCannotBuyOwnProduct() {
            assertThatThrownBy(() -> Trade.reserve(product, seller))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TRADE_SELF_NOT_ALLOWED);
        }
    }

    @Nested
    @DisplayName("상태 전이 — 정상 경로")
    class TransitionsHappyPath {

        @Test
        @DisplayName("confirm() — RESERVED 에서 CONFIRMED 로 전이 (Product 상태는 그대로 TRADING)")
        void confirm_fromReserved_toConfirmed() {
            Trade trade = Trade.reserve(product, buyer);

            trade.confirm();

            assertThat(trade.getStatus()).isEqualTo(TradeStatus.CONFIRMED);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.TRADING);
        }

        @Test
        @DisplayName("settle() — CONFIRMED 에서 SETTLED 로 전이 + Product 는 SOLD")
        void settle_fromConfirmed_toSettled() {
            Trade trade = Trade.reserve(product, buyer);
            trade.confirm();

            trade.settle();

            assertThat(trade.getStatus()).isEqualTo(TradeStatus.SETTLED);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD);
        }

        @Test
        @DisplayName("cancel() — RESERVED 에서 CANCELED 로 전이 + Product 는 AVAILABLE 로 복귀")
        void cancel_fromReserved_toCanceled() {
            Trade trade = Trade.reserve(product, buyer);

            trade.cancel();

            assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELED);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.AVAILABLE);
        }
    }

    @Nested
    @DisplayName("상태 전이 — 가드(잘못된 상태에서 호출)")
    class TransitionsGuards {

        @Test
        @DisplayName("confirm() 을 RESERVED 가 아닌 상태에 호출하면 INVALID_TRADE_TRANSITION")
        void confirm_whenNotReserved_throws() {
            Trade trade = Trade.reserve(product, buyer);
            trade.confirm();   // → CONFIRMED

            assertThatThrownBy(trade::confirm)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TRADE_TRANSITION);
        }

        @Test
        @DisplayName("settle() 을 RESERVED 상태에 호출하면 INVALID_TRADE_TRANSITION (CONFIRMED 거쳐야 함)")
        void settle_whenReserved_throws() {
            Trade trade = Trade.reserve(product, buyer);

            assertThatThrownBy(trade::settle)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TRADE_TRANSITION);
        }

        @Test
        @DisplayName("cancel() 은 이번 PR 에서 RESERVED 단계만 허용 — CONFIRMED 취소는 INVALID_TRADE_TRANSITION")
        void cancel_whenConfirmed_throws() {
            Trade trade = Trade.reserve(product, buyer);
            trade.confirm();

            assertThatThrownBy(trade::cancel)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TRADE_TRANSITION);
        }

        @Test
        @DisplayName("SETTLED 는 종착 상태 — 어떤 전이도 받지 않음")
        void settledIsTerminal() {
            Trade trade = Trade.reserve(product, buyer);
            trade.confirm();
            trade.settle();   // → SETTLED

            assertThatThrownBy(trade::confirm).isInstanceOf(BusinessException.class);
            assertThatThrownBy(trade::settle).isInstanceOf(BusinessException.class);
            assertThatThrownBy(trade::cancel).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("CANCELED 도 종착 상태 — 어떤 전이도 받지 않음")
        void canceledIsTerminal() {
            Trade trade = Trade.reserve(product, buyer);
            trade.cancel();   // → CANCELED

            assertThatThrownBy(trade::confirm).isInstanceOf(BusinessException.class);
            assertThatThrownBy(trade::settle).isInstanceOf(BusinessException.class);
            assertThatThrownBy(trade::cancel).isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("참여자 검증 헬퍼")
    class Participants {

        @Test
        @DisplayName("isBuyer() — 구매자 본인의 id 는 true, 그 외는 false")
        void isBuyer() {
            Trade trade = Trade.reserve(product, buyer);

            assertThat(trade.isBuyer(200L)).isTrue();
            assertThat(trade.isBuyer(100L)).isFalse();
            assertThat(trade.isBuyer(999L)).isFalse();
        }

        @Test
        @DisplayName("isSeller() — Product.seller 의 id 와 비교")
        void isSeller() {
            Trade trade = Trade.reserve(product, buyer);

            assertThat(trade.isSeller(100L)).isTrue();
            assertThat(trade.isSeller(200L)).isFalse();
        }
    }
}
