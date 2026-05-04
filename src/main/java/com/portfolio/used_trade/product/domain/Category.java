package com.portfolio.used_trade.product.domain;

import com.portfolio.used_trade.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 카테고리 마스터.
 *
 * <p><b>왜 enum 이 아니라 엔티티인가?</b>
 * <ul>
 *   <li>중고거래는 카테고리 추가/변경이 잦은 도메인 — enum 으로 두면 변경마다 배포 필요</li>
 *   <li>{@code Product → Category} 외래키 관계로 검색·집계·필터 쿼리가 자연스러움</li>
 *   <li>운영자가 표시 순서/활성 여부를 데이터로 통제 가능</li>
 * </ul>
 *
 * <p><b>설계 원칙</b>
 * <ul>
 *   <li>setter 미노출 — 의도 메서드만 노출
 *       ({@link #rename(String)}, {@link #changeDisplayOrder(int)}, {@link #activate()}, {@link #deactivate()})</li>
 *   <li>이름은 비즈니스 키이므로 UNIQUE — 중복 시드 방지 + 외부 노출 라벨</li>
 *   <li>삭제 대신 {@link #deactivate()} — Product FK 가 깨지지 않도록 soft-disable</li>
 * </ul>
 *
 * <p><b>인덱스</b>
 * <ul>
 *   <li>{@code uk_categories_name} : name UNIQUE — 시드 idempotency + 사용자 표시 라벨 중복 방지</li>
 * </ul>
 */
@Entity
@Table(
        name = "categories",
        indexes = {
                @Index(name = "uk_categories_name", columnList = "name", unique = true)
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사용자에게 노출되는 표시명. UNIQUE. */
    @Column(nullable = false, length = 50)
    private String name;

    /** 목록 정렬 순서. 작을수록 위. 동률 허용 (안정 정렬은 id 보조). */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    /** 비활성화된 카테고리는 신규 등록 화면에서 숨김. 기존 Product 는 그대로 유지. */
    @Column(nullable = false)
    private boolean active;

    // ---------- 생성 ----------

    /**
     * 카테고리 신규 등록 정적 팩토리.
     *
     * @param name         표시명 (UNIQUE 제약 → 호출자 책임으로 중복 검사 선행)
     * @param displayOrder 표시 순서 (작을수록 상단)
     */
    public static Category create(String name, int displayOrder) {
        Category category = new Category();
        category.name = name;
        category.displayOrder = displayOrder;
        category.active = true;
        return category;
    }

    // ---------- 도메인 동작 ----------

    /** 표시명 변경. 비즈니스 규칙(중복 검증 등) 은 서비스 레이어 책임. */
    public void rename(String name) {
        this.name = name;
    }

    /** 표시 순서 변경. */
    public void changeDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    /** 신규 등록 화면에서 숨김. 기존 상품의 FK 는 유지. */
    public void deactivate() {
        this.active = false;
    }

    /** 다시 활성. */
    public void activate() {
        this.active = true;
    }
}
