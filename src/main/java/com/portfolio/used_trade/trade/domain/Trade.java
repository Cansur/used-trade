package com.portfolio.used_trade.trade.domain;

import com.portfolio.used_trade.common.domain.BaseEntity;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Product;
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
 * 거래 엔티티 — ADR-2 (낙관적 락 + Spring Retry) 의 핵심 단위.
 *
 * <p><b>설계 원칙</b>
 * <ul>
 *   <li>setter 미노출 — 변경은 도메인 메서드 ({@link #confirm}, {@link #settle}, {@link #cancel})</li>
 *   <li>상태 전이는 가드된다 — 잘못된 상태에서 호출하면 {@link ErrorCode#INVALID_TRADE_TRANSITION}</li>
 *   <li>예약 시 {@link Product#reserve()} 를 함께 호출해 상품 상태 머신과 결합 —
 *       Product 의 {@code @Version} 충돌이 곧 거래 충돌</li>
 *   <li>{@code pricePaid} 는 거래 시점 가격 스냅샷 — Product.price 가 사후에 바뀌어도 거래 금액은 고정</li>
 * </ul>
 *
 * <p><b>관계</b>
 * <ul>
 *   <li>{@code product} : Product 단방향 ManyToOne (LAZY) — 거래 대상</li>
 *   <li>{@code buyer}   : User 단방향 ManyToOne (LAZY) — 구매자
 *       (판매자는 {@code product.seller} 로 파생; 거래 중 변경 불가)</li>
 * </ul>
 *
 * <p><b>인덱스</b>
 * <ul>
 *   <li>{@code idx_trades_product}    : 상품별 거래 이력 ({@code WHERE product_id = ?})</li>
 *   <li>{@code idx_trades_buyer}      : 내 구매 이력</li>
 *   <li>{@code idx_trades_status_id}  : 상태별 조회 (운영/관리)</li>
 * </ul>
 *
 * <p><b>낙관적 락</b><br>
 * Trade 자체에도 {@link Version} 을 두어 동일 거래에 대한 동시 confirm/settle/cancel 도
 * 검출한다. 단, 이번 PR 의 핵심은 <b>Product 의 {@code @Version}</b> — 동시 예약(reserve)
 * 충돌 시 1명만 성공하고 나머지는 OptimisticLockException 을 받는다.
 */
@Entity
@Table(
        name = "trades",
        indexes = {
                @Index(name = "idx_trades_product", columnList = "product_id"),
                @Index(name = "idx_trades_buyer", columnList = "buyer_id"),
                @Index(name = "idx_trades_status_id", columnList = "status,id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trade extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    /** 거래 시점 Product.price 스냅샷. KRW 원 단위 정수. */
    @Column(name = "price_paid", nullable = false)
    private Long pricePaid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TradeStatus status;

    /** 낙관적 락 버전. 동일 trade row 동시 수정 검출. */
    @Version
    private Long version;

    // ---------- 생성 ----------

    /**
     * 거래 예약 정적 팩토리.
     *
     * <p>다음 일이 한 번에 일어난다:
     * <ol>
     *   <li>본인 상품 예약 차단 — {@link ErrorCode#TRADE_SELF_NOT_ALLOWED}</li>
     *   <li>{@link Product#reserve()} — AVAILABLE → TRADING. 이미 TRADING/SOLD 면
     *       {@link ErrorCode#PRODUCT_NOT_AVAILABLE}</li>
     *   <li>Trade 인스턴스 생성 — 상태 RESERVED, 가격은 거래 시점 Product.price 스냅샷</li>
     * </ol>
     *
     * <p>JPA 가 saveAndFlush 시 Product 의 {@code @Version} 충돌을
     * OptimisticLockException 으로 던지면, 호출자(서비스)가 Spring Retry 로 재시도한다.
     */
    public static Trade reserve(Product product, User buyer) {
        if (product.isOwnedBy(buyer.getId())) {
            throw new BusinessException(ErrorCode.TRADE_SELF_NOT_ALLOWED);
        }
        product.reserve();   // AVAILABLE → TRADING (가드 + @Version 충돌 지점)

        Trade trade = new Trade();
        trade.product = product;
        trade.buyer = buyer;
        trade.pricePaid = product.getPrice();
        trade.status = TradeStatus.RESERVED;
        return trade;
    }

    // ---------- 상태 전이 ----------

    /** RESERVED → CONFIRMED. 다음 PR (결제 합류) 에서 호출. */
    public void confirm() {
        if (this.status != TradeStatus.RESERVED) {
            throw new BusinessException(ErrorCode.INVALID_TRADE_TRANSITION);
        }
        this.status = TradeStatus.CONFIRMED;
    }

    /**
     * CONFIRMED → SETTLED. Product 도 {@link Product#markSold()} 로 종착.
     */
    public void settle() {
        if (this.status != TradeStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.INVALID_TRADE_TRANSITION);
        }
        this.product.markSold();
        this.status = TradeStatus.SETTLED;
    }

    /**
     * RESERVED → CANCELED. Product 는 AVAILABLE 로 복귀.
     *
     * <p>이번 PR 범위는 RESERVED 단계 취소만 — CONFIRMED 취소는 환불 로직과 함께 다음 PR.
     */
    public void cancel() {
        if (this.status != TradeStatus.RESERVED) {
            throw new BusinessException(ErrorCode.INVALID_TRADE_TRANSITION);
        }
        this.product.cancelReservation();
        this.status = TradeStatus.CANCELED;
    }

    // ---------- 참여자 검증 헬퍼 ----------

    public boolean isBuyer(Long userId) {
        return this.buyer.getId().equals(userId);
    }

    public boolean isSeller(Long userId) {
        return this.product.isOwnedBy(userId);
    }
}
