package com.portfolio.used_trade.product.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.domain.ProductStatus;
import com.portfolio.used_trade.product.dto.ProductCursorPageResponse;
import com.portfolio.used_trade.product.dto.ProductRegisterRequest;
import com.portfolio.used_trade.product.dto.ProductResponse;
import com.portfolio.used_trade.product.dto.ProductUpdateRequest;
import com.portfolio.used_trade.product.repository.CategoryRepository;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ProductService} 단위 테스트.
 *
 * <p>실제 DB / JPA 컨텍스트는 사용하지 않는다 — 비즈니스 규칙만 Mockito 로 검증.
 * 통합 테스트(트랜잭션, LAZY, FK 제약 등)는 별도 영역.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService 단위 테스트")
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private ProductService productService;

    private User seller;
    private User otherUser;
    private Category electronics;

    @BeforeEach
    void setUp() {
        seller = User.create("seller@used-trade.com", "$2a$10$DUMMY", "판매자");
        ReflectionTestUtils.setField(seller, "id", 100L);

        otherUser = User.create("other@used-trade.com", "$2a$10$DUMMY", "타인");
        ReflectionTestUtils.setField(otherUser, "id", 999L);

        electronics = Category.create("전자기기", 1);
        ReflectionTestUtils.setField(electronics, "id", 1L);
    }

    private Product makeProduct(ProductStatus status) {
        Product p = Product.create(seller, electronics, "아이폰 15", "박스 미개봉", 1_200_000L);
        ReflectionTestUtils.setField(p, "id", 10L);
        if (status != ProductStatus.AVAILABLE) {
            ReflectionTestUtils.setField(p, "status", status);
        }
        return p;
    }

    private Product makeProductWithId(long id) {
        Product p = Product.create(seller, electronics, "title-" + id, "desc-" + id, 1000L * id);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    // ============================================================
    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("정상 — 사용자/카테고리 존재 시 저장하고 응답 DTO 반환")
        void happy() {
            // ── Arrange ──
            var request = new ProductRegisterRequest(1L, "아이폰 15", "박스 미개봉", 1_200_000L);
            given(userRepository.findById(100L)).willReturn(Optional.of(seller));
            given(categoryRepository.findById(1L)).willReturn(Optional.of(electronics));
            given(productRepository.save(any(Product.class))).willAnswer(inv -> {
                Product p = inv.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 10L);
                return p;
            });

            // ── Act ──
            ProductResponse response = productService.register(100L, request);

            // ── Assert ──
            assertThat(response.id()).isEqualTo(10L);
            assertThat(response.sellerId()).isEqualTo(100L);
            assertThat(response.sellerNickname()).isEqualTo("판매자");
            assertThat(response.categoryId()).isEqualTo(1L);
            assertThat(response.categoryName()).isEqualTo("전자기기");
            assertThat(response.title()).isEqualTo("아이폰 15");
            assertThat(response.price()).isEqualTo(1_200_000L);
            assertThat(response.status()).isEqualTo(ProductStatus.AVAILABLE);
        }

        @Test
        @DisplayName("토큰은 유효하지만 사용자가 DB 에 없으면 USER_NOT_FOUND")
        void userNotFound() {
            var request = new ProductRegisterRequest(1L, "t", "d", 100L);
            given(userRepository.findById(100L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> productService.register(100L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);

            // 사용자 없으면 카테고리 조회/저장까지 가지 않아야 함 (불필요 비용 방지)
            verify(categoryRepository, never()).findById(any());
            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("잘못된 카테고리 id 면 CATEGORY_NOT_FOUND")
        void categoryNotFound() {
            var request = new ProductRegisterRequest(99L, "t", "d", 100L);
            given(userRepository.findById(100L)).willReturn(Optional.of(seller));
            given(categoryRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> productService.register(100L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);

            verify(productRepository, never()).save(any());
        }
    }

    // ============================================================
    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("정상 — 존재하는 상품의 응답 DTO 반환 (status 포함)")
        void happy() {
            given(productRepository.findById(10L)).willReturn(Optional.of(makeProduct(ProductStatus.AVAILABLE)));

            ProductResponse response = productService.findById(10L);

            assertThat(response.id()).isEqualTo(10L);
            assertThat(response.status()).isEqualTo(ProductStatus.AVAILABLE);
            assertThat(response.sellerNickname()).isEqualTo("판매자");
        }

        @Test
        @DisplayName("없는 id — PRODUCT_NOT_FOUND")
        void notFound() {
            given(productRepository.findById(404L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> productService.findById(404L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    // ============================================================
    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("정상 — 일부 필드만 변경 (null 필드는 미변경)")
        void happyPartial() {
            // ── Arrange ──
            Product product = makeProduct(ProductStatus.AVAILABLE);
            given(productRepository.findById(10L)).willReturn(Optional.of(product));

            // 제목만 바꾸고 나머지는 null → 변경 없음
            var request = new ProductUpdateRequest(null, "아이폰 15 Pro", null, null);

            // ── Act ──
            ProductResponse response = productService.update(100L, 10L, request);

            // ── Assert ──
            assertThat(response.title()).isEqualTo("아이폰 15 Pro");
            assertThat(response.description()).isEqualTo("박스 미개봉");   // 미변경
            assertThat(response.price()).isEqualTo(1_200_000L);            // 미변경
            assertThat(response.categoryName()).isEqualTo("전자기기");      // 미변경

            // 카테고리 변경이 없었으므로 카테고리 repo 는 호출되지 않아야 함 (불필요 비용 방지)
            verify(categoryRepository, never()).findById(any());
        }

        @Test
        @DisplayName("타인이 수정 시도 — NOT_PRODUCT_OWNER")
        void notOwner() {
            given(productRepository.findById(10L)).willReturn(Optional.of(makeProduct(ProductStatus.AVAILABLE)));
            var request = new ProductUpdateRequest(null, "해킹된 제목", null, null);

            // sellerId = 999L (otherUser) — 실제 owner 는 100L
            assertThatThrownBy(() -> productService.update(999L, 10L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_PRODUCT_OWNER);
        }

        @Test
        @DisplayName("SOLD 상태 상품 수정 거부 — PRODUCT_NOT_AVAILABLE")
        void soldRefused() {
            given(productRepository.findById(10L)).willReturn(Optional.of(makeProduct(ProductStatus.SOLD)));
            var request = new ProductUpdateRequest(null, "수정", null, null);

            assertThatThrownBy(() -> productService.update(100L, 10L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("카테고리 변경 시 잘못된 id — CATEGORY_NOT_FOUND")
        void categoryChangeNotFound() {
            given(productRepository.findById(10L)).willReturn(Optional.of(makeProduct(ProductStatus.AVAILABLE)));
            given(categoryRepository.findById(99L)).willReturn(Optional.empty());
            var request = new ProductUpdateRequest(99L, null, null, null);

            assertThatThrownBy(() -> productService.update(100L, 10L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
        }
    }

    // ============================================================
    @Nested
    @DisplayName("list() — 커서 페이징")
    class List_ {

        @Test
        @DisplayName("빈 결과 — items 비어있고 hasNext=false, nextCursor=null")
        void empty() {
            given(productRepository.findAvailableByCursor(any(), any(), any(), any()))
                    .willReturn(List.of());

            ProductCursorPageResponse response = productService.list(null, null, null, 10);

            assertThat(response.items()).isEmpty();
            assertThat(response.nextCursor()).isNull();
            assertThat(response.hasNext()).isFalse();
        }

        @Test
        @DisplayName("부분 페이지 (rows < size+1) — hasNext=false, nextCursor=null")
        void partialNoNext() {
            // size=10 요청, repo 가 3건만 반환 (마지막 페이지)
            given(productRepository.findAvailableByCursor(any(), any(), any(), any()))
                    .willReturn(List.of(makeProductWithId(3L), makeProductWithId(2L), makeProductWithId(1L)));

            ProductCursorPageResponse response = productService.list(null, null, null, 10);

            assertThat(response.items()).hasSize(3);
            assertThat(response.nextCursor()).isNull();
            assertThat(response.hasNext()).isFalse();
        }

        @Test
        @DisplayName("size+1 행 (hasNext=true 트리거) — items=size, nextCursor=마지막 id, 응답에서 +1째 행은 잘림")
        void fullHasNext() {
            int size = 3;
            // repo 가 size+1=4건 반환 → 응답엔 정확히 3건만 노출
            given(productRepository.findAvailableByCursor(any(), any(), any(), any()))
                    .willReturn(List.of(
                            makeProductWithId(10L),
                            makeProductWithId(9L),
                            makeProductWithId(8L),
                            makeProductWithId(7L)   // 응답에선 잘림 (size+1째)
                    ));

            ProductCursorPageResponse response = productService.list(null, null, null, size);

            assertThat(response.items()).hasSize(3);
            assertThat(response.items().get(0).id()).isEqualTo(10L);
            assertThat(response.items().get(2).id()).isEqualTo(8L);
            assertThat(response.nextCursor()).isEqualTo(8L);     // 응답 페이지의 마지막 id
            assertThat(response.hasNext()).isTrue();
        }

        @Test
        @DisplayName("size 100 요청 → 50 으로 클램핑 (Repository 에 size+1=51 전달)")
        void clampsLargeSize() {
            given(productRepository.findAvailableByCursor(any(), any(), any(), any()))
                    .willReturn(List.of());

            productService.list(null, null, null, 100);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(productRepository).findAvailableByCursor(any(), any(), any(), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(51);
        }

        @Test
        @DisplayName("size 0 / 음수 요청 → 1 로 클램핑 (Repository 에 size+1=2 전달)")
        void clampsSmallSize() {
            given(productRepository.findAvailableByCursor(any(), any(), any(), any()))
                    .willReturn(List.of());

            productService.list(null, null, null, 0);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(productRepository).findAvailableByCursor(any(), any(), any(), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("cursor / categoryId / sellerId 는 변형 없이 그대로 Repository 에 전달")
        void passesFiltersThrough() {
            given(productRepository.findAvailableByCursor(any(), any(), any(), any()))
                    .willReturn(List.of());

            productService.list(99L, 1L, 100L, 10);

            verify(productRepository).findAvailableByCursor(eq(99L), eq(1L), eq(100L), any());
        }
    }

    // ============================================================
    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("정상 — 본인 + 미판매 상품 삭제")
        void happy() {
            Product product = makeProduct(ProductStatus.AVAILABLE);
            given(productRepository.findById(10L)).willReturn(Optional.of(product));

            productService.delete(100L, 10L);

            verify(productRepository).delete(product);
        }

        @Test
        @DisplayName("타인이 삭제 시도 — NOT_PRODUCT_OWNER, delete 호출되지 않음")
        void notOwner() {
            given(productRepository.findById(10L)).willReturn(Optional.of(makeProduct(ProductStatus.AVAILABLE)));

            assertThatThrownBy(() -> productService.delete(999L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_PRODUCT_OWNER);

            verify(productRepository, never()).delete(any());
        }

        @Test
        @DisplayName("SOLD 상품 삭제 거부 — 거래 이력 보존")
        void soldRefused() {
            given(productRepository.findById(10L)).willReturn(Optional.of(makeProduct(ProductStatus.SOLD)));

            assertThatThrownBy(() -> productService.delete(100L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);

            verify(productRepository, never()).delete(any());
        }
    }
}
