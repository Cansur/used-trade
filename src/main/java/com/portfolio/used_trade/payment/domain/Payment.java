package com.portfolio.used_trade.payment.domain;

import com.portfolio.used_trade.common.domain.BaseEntity;
import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.trade.domain.Trade;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 엔티티 — Saga 의 "T1 보상 가능" 단계.
 *
 * <p><b>설계 원칙</b>
 * <ul>
 *   <li>setter 미노출 — 변경은 {@link #markPaid}, {@link #markFailed}, {@link #refund}</li>
 *   <li>한 trade 당 하나의 Payment row 만 — UNIQUE (trade_id) 강제</li>
 *   <li>amount 는 거래 시점 가격 그대로 ({@link Trade#getPricePaid()})</li>
 *   <li>gatewayTxId 는 Mock PG 의 응답 값 (운영은 외부 PG 의 거래번호)</li>
 * </ul>
 *
 * <p><b>인덱스</b>
 * <ul>
 *   <li>{@code uk_payments_trade}      : (trade_id) UNIQUE — 중복 결제 차단</li>
 *   <li>{@code idx_payments_status}    : 상태별 조회 (PENDING 만 모아서 재시도 등)</li>
 * </ul>
 *
 * <p>운영에서 Saga 의 결제 단계 결과 추적 / 정산 / 환불 처리의 근거가 되는 row.
 */
@Entity
@Table(
        name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payments_trade", columnNames = "trade_id")
        },
        indexes = {
                @Index(name = "idx_payments_status", columnList = "status")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;

    /** 결제 금액. KRW 정수원. Trade.pricePaid 와 동일. */
    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    /** Mock PG 가 부여한 거래번호. PENDING 단계에선 null, charge 후 채움. */
    @Column(name = "gateway_tx_id", length = 64)
    private String gatewayTxId;

    // ---------- 생성 ----------

    /**
     * 결제 시도 직전 PENDING 으로 생성. Saga 진입 직후 호출.
     */
    public static Payment initiate(Trade trade, Long amount) {
        Payment payment = new Payment();
        payment.trade = trade;
        payment.amount = amount;
        payment.status = PaymentStatus.PENDING;
        return payment;
    }

    // ---------- 상태 전이 ----------

    /**
     * Mock PG 응답 성공 → PAID. PENDING 외 상태에서는 {@code PAYMENT_ALREADY_PROCESSED}.
     */
    public void markPaid(String gatewayTxId) {
        if (this.status != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        this.status = PaymentStatus.PAID;
        this.gatewayTxId = gatewayTxId;
    }

    /**
     * Mock PG 응답 실패 → FAILED. PENDING 외 상태에서는 {@code PAYMENT_ALREADY_PROCESSED}.
     */
    public void markFailed() {
        if (this.status != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        this.status = PaymentStatus.FAILED;
    }

    /**
     * 환불 처리 — PAID 만 가능. 다음 PR (CONFIRMED 취소) 에서 활용.
     */
    public void refund() {
        if (this.status != PaymentStatus.PAID) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        this.status = PaymentStatus.REFUNDED;
    }
}
