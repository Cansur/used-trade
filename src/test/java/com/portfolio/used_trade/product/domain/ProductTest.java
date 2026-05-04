package com.portfolio.used_trade.product.domain;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Product} 도메인 단위 테스트.
 *
 * <p>의도: 정적 팩토리의 초기 상태, 상태 전이 가드, 소유자 검증 헬퍼,
 * change* 의도 메서드의 변경 반영을 회귀 테스트로 박는다.
 *
 * <p>여기서 검증하지 않는 것:
 * <ul>
 *   <li>가격/제목 입력 검증 — 서비스 레이어 책임</li>
 *   <li>JPA 동작(영속화, FK 제약) — 통합 테스트 영역</li>
 * </ul>
 */
@DisplayName("Product 도메인 단위 테스트")
class ProductTest {

    private User seller;
    private Category category;

    @BeforeEach
    void setUp() {
        seller = User.create("seller@used-trade.com", "$2a$10$DUMMYHASH", "판매자");
        ReflectionTestUtils.setField(seller, "id", 100L);
        category = Category.create("전자기기", 1);
        ReflectionTestUtils.setField(category, "id", 1L);
    }

    private Product newProduct() {
        return Product.create(seller, category, "아이폰 15", "박스 미개봉", 1_200_000L);
    }

    @Nested
    @DisplayName("정적 팩토리 create()")
    class Factory {

        @Test
        @DisplayName("생성 직후 상태는 AVAILABLE 이고 모든 필드가 정확히 할당된다")
        void create_initialState() {
            Product product = newProduct();

            assertThat(product.getStatus()).isEqualTo(ProductStatus.AVAILABLE);
            assertThat(product.getSeller()).isSameAs(seller);
            assertThat(product.getCategory()).isSameAs(category);
            assertThat(product.getTitle()).isEqualTo("아이폰 15");
            assertThat(product.getDescription()).isEqualTo("박스 미개봉");
            assertThat(product.getPrice()).isEqualTo(1_200_000L);
        }
    }

    @Nested
    @DisplayName("상태 전이 — 정상 경로")
    class TransitionsHappyPath {

        @Test
        @DisplayName("reserve() — AVAILABLE 에서 TRADING 으로 전이")
        void reserve_fromAvailable_toTrading() {
            Product product = newProduct();

            product.reserve();

            assertThat(product.getStatus()).isEqualTo(ProductStatus.TRADING);
        }

        @Test
        @DisplayName("markSold() — TRADING 에서 SOLD 로 전이")
        void markSold_fromTrading_toSold() {
            Product product = newProduct();
            product.reserve();   // AVAILABLE → TRADING

            product.markSold();

            assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD);
        }

        @Test
        @DisplayName("cancelReservation() — TRADING 에서 AVAILABLE 로 복귀")
        void cancelReservation_fromTrading_toAvailable() {
            Product product = newProduct();
            product.reserve();   // AVAILABLE → TRADING

            product.cancelReservation();

            assertThat(product.getStatus()).isEqualTo(ProductStatus.AVAILABLE);
        }
    }

    @Nested
    @DisplayName("상태 전이 — 가드(잘못된 상태에서 호출)")
    class TransitionsGuards {

        @Test
        @DisplayName("reserve() 를 이미 TRADING 인 상품에 호출하면 PRODUCT_NOT_AVAILABLE 예외")
        void reserve_whenAlreadyTrading_throws() {
            Product product = newProduct();
            product.reserve();

            assertThatThrownBy(product::reserve)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("markSold() 를 AVAILABLE 인 상품에 호출하면 PRODUCT_NOT_AVAILABLE 예외")
        void markSold_whenAvailable_throws() {
            Product product = newProduct();   // AVAILABLE 그대로

            assertThatThrownBy(product::markSold)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("cancelReservation() 을 AVAILABLE 인 상품에 호출하면 PRODUCT_NOT_AVAILABLE 예외")
        void cancelReservation_whenAvailable_throws() {
            Product product = newProduct();   // AVAILABLE 그대로

            assertThatThrownBy(product::cancelReservation)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("SOLD 는 종착 상태 — 어떤 전이도 받지 않음")
        void soldIsTerminal() {
            Product product = newProduct();
            product.reserve();
            product.markSold();   // → SOLD

            assertThatThrownBy(product::reserve)
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(product::markSold)
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(product::cancelReservation)
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("소유자 검증 헬퍼 isOwnedBy()")
    class Ownership {

        @Test
        @DisplayName("판매자 본인의 id 는 true, 다른 id 는 false")
        void isOwnedBy_returnsTrueOnlyForSeller() {
            Product product = newProduct();   // seller.id = 100L

            assertThat(product.isOwnedBy(100L)).isTrue();
            assertThat(product.isOwnedBy(999L)).isFalse();
        }
    }

    @Nested
    @DisplayName("수정 메서드 change*()")
    class Mutators {

        @Test
        @DisplayName("title / description / price / category 변경이 정상 반영된다")
        void changeFieldsReflected() {
            Product product = newProduct();
            Category newCategory = Category.create("의류/패션", 2);
            ReflectionTestUtils.setField(newCategory, "id", 2L);

            product.changeTitle("아이폰 15 Pro");
            product.changeDescription("케이스 포함");
            product.changePrice(1_350_000L);
            product.changeCategory(newCategory);

            assertThat(product.getTitle()).isEqualTo("아이폰 15 Pro");
            assertThat(product.getDescription()).isEqualTo("케이스 포함");
            assertThat(product.getPrice()).isEqualTo(1_350_000L);
            assertThat(product.getCategory()).isSameAs(newCategory);
        }
    }
}
