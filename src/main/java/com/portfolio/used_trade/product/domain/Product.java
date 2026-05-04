package com.portfolio.used_trade.product.domain;

import com.portfolio.used_trade.common.domain.BaseEntity;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 엔티티.
 *
 * <p><b>설계 원칙</b>
 * <ul>
 *   <li>setter 미노출 — 변경은 의도 메서드로만
 *       ({@link #changeTitle}, {@link #changePrice}, {@link #changeCategory}, ...)</li>
 *   <li>상태 전이는 도메인 메서드가 가드 — {@link #reserve}, {@link #markSold},
 *       {@link #cancelReservation} — 잘못된 상태에서 호출하면 {@link BusinessException}</li>
 *   <li>가격은 KRW 정수원 단위({@code Long}) — 다국적 결제 들어가면 {@code BigDecimal} 로 이전</li>
 * </ul>
 *
 * <p><b>관계</b>
 * <ul>
 *   <li>{@code seller}    : User 단방향 ManyToOne (LAZY) — user 도메인은 product 를 모름</li>
 *   <li>{@code category}  : Category 단방향 ManyToOne (LAZY) — 같은 product 패키지 내부 의존</li>
 * </ul>
 *
 * <p><b>인덱스</b>
 * <ul>
 *   <li>{@code idx_products_seller}     : 내 상품 목록 조회 ({@code WHERE seller_id = ?})</li>
 *   <li>{@code idx_products_category}   : 카테고리별 목록 조회</li>
 *   <li>{@code idx_products_status_id}  : 상태 필터 + id 정렬 (커서 페이징 후보 인덱스)</li>
 * </ul>
 *
 * <p><b>낙관적 락</b><br>
 * {@link Version} 필드는 자리만 잡았다 — Phase 2 의 trade 도메인이 합류하면
 * Spring Retry 와 함께 동시 예약 충돌을 방어하는 ADR-2 의 핵심으로 사용한다.
 * Phase 1.5 (W1 Day 5) 에서는 필드만 두고 retry 로직 미적용.
 */
@Entity
@Table(
        name = "products",
        indexes = {
                @Index(name = "idx_products_seller", columnList = "seller_id"),
                @Index(name = "idx_products_category", columnList = "category_id"),
                @Index(name = "idx_products_status_id", columnList = "status,id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 100)
    private String title;

    /** 본문. MySQL TEXT 컬럼 매핑 — 길이 제한 없이 저장. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    /** KRW 원 단위 정수. 음수 검증은 서비스 레이어 책임. */
    @Column(nullable = false)
    private Long price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    /** 낙관적 락 버전. trade 도메인 합류 시 활성화. */
    @Version
    private Long version;

    // ---------- 생성 ----------

    /**
     * 상품 등록 정적 팩토리. 초기 상태는 항상 {@link ProductStatus#AVAILABLE}.
     * 입력 검증(빈 값, 가격 범위 등)은 서비스 레이어 책임.
     */
    public static Product create(User seller, Category category,
                                 String title, String description, long price) {
        Product product = new Product();
        product.seller = seller;
        product.category = category;
        product.title = title;
        product.description = description;
        product.price = price;
        product.status = ProductStatus.AVAILABLE;
        return product;
    }

    // ---------- 수정 (의도 있는 setter 대체) ----------

    public void changeTitle(String title) {
        this.title = title;
    }

    public void changeDescription(String description) {
        this.description = description;
    }

    public void changePrice(long price) {
        this.price = price;
    }

    public void changeCategory(Category category) {
        this.category = category;
    }

    // ---------- 상태 전이 ----------

    /** 판매중 → 예약중. 이미 예약/판매완료면 {@code PRODUCT_NOT_AVAILABLE}. */
    public void reserve() {
        if (this.status != ProductStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }
        this.status = ProductStatus.TRADING;
    }

    /** 예약중 → 판매완료. 그 외 상태에서 호출은 {@code PRODUCT_NOT_AVAILABLE}. */
    public void markSold() {
        if (this.status != ProductStatus.TRADING) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }
        this.status = ProductStatus.SOLD;
    }

    /** 예약 취소 — 예약중 → 판매중. 그 외 상태에서 호출은 {@code PRODUCT_NOT_AVAILABLE}. */
    public void cancelReservation() {
        if (this.status != ProductStatus.TRADING) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }
        this.status = ProductStatus.AVAILABLE;
    }

    // ---------- 소유 검증 헬퍼 ----------

    /**
     * 주어진 사용자 ID 가 이 상품의 판매자인지.
     * 컨트롤러/서비스가 {@code AuthUser.id()} 와 비교해 소유자 검증 수행.
     */
    public boolean isOwnedBy(Long userId) {
        return this.seller.getId().equals(userId);
    }
}
