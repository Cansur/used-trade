package com.portfolio.used_trade.trade.repository;

import com.portfolio.used_trade.trade.domain.Trade;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 거래 영속성 어댑터.
 *
 * <p>이번 PR 은 단건 저장/조회만 사용 — 목록/페이징은 다음 PR
 * (구매 이력, 판매 이력) 에서 추가.
 */
public interface TradeRepository extends JpaRepository<Trade, Long> {
}
