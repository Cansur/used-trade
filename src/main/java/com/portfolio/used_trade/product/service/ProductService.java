package com.portfolio.used_trade.product.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.domain.ProductStatus;
import com.portfolio.used_trade.product.dto.ProductRegisterRequest;
import com.portfolio.used_trade.product.dto.ProductResponse;
import com.portfolio.used_trade.product.dto.ProductUpdateRequest;
import com.portfolio.used_trade.product.repository.CategoryRepository;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 도메인 핵심 비즈니스 로직.
 *
 * <p>책임:
 * <ul>
 *   <li>등록/조회/부분수정/삭제 (상품 목록·페이징은 별도 단계에서 분리)</li>
 *   <li>소유자 검증 — 본인 상품만 수정/삭제</li>
 *   <li>SOLD 상태 가드 — 종착 상태에서는 수정/삭제 거부 (거래 이력 보존)</li>
 *   <li>카테고리 / 사용자 존재 검증</li>
 * </ul>
 *
 * <p><b>왜 클래스에 {@code @Transactional(readOnly = true)} 인가?</b>
 * 읽기 전용 메서드는 dirty checking 을 건너뛰어 약간 빠르고, 쓰기 메서드는
 * 메서드 단위로 {@code @Transactional} 을 다시 걸어 의도를 명시한다 — UserService 와 동일 패턴.
 *
 * <p><b>왜 응답 변환을 서비스에서 하는가?</b>
 * {@code spring.jpa.open-in-view=false} 환경에서 컨트롤러는 트랜잭션 밖. LAZY
 * 연관(seller.nickname, category.name) 을 거기서 접근하면 LazyInitializationException.
 * Service 가 트랜잭션 종료 전 {@link ProductResponse#from(Product)} 로 미리 펼친다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    /**
     * 상품 등록. 인증된 사용자 id 와 함께 호출된다.
     *
     * @throws BusinessException {@link ErrorCode#USER_NOT_FOUND} 토큰은 유효하지만 DB 에선 사라진 사용자
     * @throws BusinessException {@link ErrorCode#CATEGORY_NOT_FOUND} 잘못된 카테고리 id
     */
    @Transactional
    public ProductResponse register(Long sellerId, ProductRegisterRequest request) {
        User seller = userRepository.findById(sellerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));

        Product product = Product.create(
                seller, category,
                request.title(), request.description(), request.price()
        );
        Product saved = productRepository.save(product);
        return ProductResponse.from(saved);
    }

    /**
     * 단건 조회. 인증 없어도 접근 가능 (목록 화면에서 상세 진입 등).
     *
     * @throws BusinessException {@link ErrorCode#PRODUCT_NOT_FOUND}
     */
    public ProductResponse findById(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        return ProductResponse.from(product);
    }

    /**
     * 부분 수정 (PATCH 의미). null 필드는 변경하지 않음.
     *
     * @throws BusinessException {@link ErrorCode#PRODUCT_NOT_FOUND}
     * @throws BusinessException {@link ErrorCode#NOT_PRODUCT_OWNER}    본인 상품 아님
     * @throws BusinessException {@link ErrorCode#PRODUCT_NOT_AVAILABLE} SOLD 상태
     * @throws BusinessException {@link ErrorCode#CATEGORY_NOT_FOUND}    잘못된 카테고리 id
     */
    @Transactional
    public ProductResponse update(Long sellerId, Long productId, ProductUpdateRequest request) {
        Product product = loadAndCheckMutable(sellerId, productId);

        if (request.title() != null) {
            product.changeTitle(request.title());
        }
        if (request.description() != null) {
            product.changeDescription(request.description());
        }
        if (request.price() != null) {
            product.changePrice(request.price());
        }
        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
            product.changeCategory(category);
        }
        // dirty checking 으로 트랜잭션 종료 시 자동 UPDATE — 명시적 save 불필요.
        return ProductResponse.from(product);
    }

    /**
     * 삭제. 본 단계는 hard delete. 거래 이력 보존이 필요해지는 시점에 soft delete 로 이전.
     *
     * @throws BusinessException {@link ErrorCode#PRODUCT_NOT_FOUND}
     * @throws BusinessException {@link ErrorCode#NOT_PRODUCT_OWNER}
     * @throws BusinessException {@link ErrorCode#PRODUCT_NOT_AVAILABLE} SOLD 상태
     */
    @Transactional
    public void delete(Long sellerId, Long productId) {
        Product product = loadAndCheckMutable(sellerId, productId);
        productRepository.delete(product);
    }

    // ---------- 내부 ----------

    /**
     * 수정/삭제 공통 진입 검증 — 존재 + 소유자 + SOLD 가드.
     * 한 메서드에서 같은 가드를 반복하지 않도록 분리.
     */
    private Product loadAndCheckMutable(Long sellerId, Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!product.isOwnedBy(sellerId)) {
            throw new BusinessException(ErrorCode.NOT_PRODUCT_OWNER);
        }
        if (product.getStatus() == ProductStatus.SOLD) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }
        return product;
    }
}
