package com.portfolio.used_trade.payment.repository;

import com.portfolio.used_trade.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 결제 영속성 어댑터.
 *
 * <p>Saga 가 trade 단위로 한 번만 charge — 같은 trade 의 두 번째 시도는 UNIQUE 위반 또는
 * existsByTradeId 가드로 차단.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Saga 진입 시 중복 결제 차단용. */
    boolean existsByTradeId(Long tradeId);

    /** 단건 조회 (운영 / 환불 처리). */
    Optional<Payment> findByTradeId(Long tradeId);
}
