package com.portfolio.used_trade.trade.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.payment.domain.PaymentStatus;
import com.portfolio.used_trade.payment.dto.PaymentResponse;
import com.portfolio.used_trade.payment.service.PaymentService;
import com.portfolio.used_trade.trade.domain.Trade;
import com.portfolio.used_trade.trade.dto.TradeConfirmResponse;
import com.portfolio.used_trade.trade.dto.TradeResponse;
import com.portfolio.used_trade.trade.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래 확정 (confirm) 의 Saga Orchestrator — payment + trade 두 단계 조율.
 *
 * <p><b>Saga 단계</b>
 * <pre>
 *   T0. validate           : trade 존재 + buyer 본인 + status RESERVED
 *   T1. payment.charge()   : 자기 트랜잭션. 결과 PAID / FAILED 모두 영속.
 *        ├─ PAID  → T2.   trade.confirm()  (RESERVED → CONFIRMED)
 *        └─ FAILED → T1'. trade.cancel()  (보상: RESERVED → CANCELED + Product 복원)
 *                          + BusinessException(PAYMENT_FAILED) throw
 * </pre>
 *
 * <p><b>왜 Saga 메서드 자체에 {@code @Transactional} 을 안 두는가?</b>
 * Saga 의 본질은 <b>각 단계가 독립 트랜잭션</b>. 묶으면 ACID 한 트랜잭션이라 Saga 의미 무너짐.
 * 각 단계 ({@link PaymentService#charge}, {@link TradeService#confirm}, {@link TradeService#cancel})
 * 가 자기 {@code @Transactional} 을 가져 commit 한 후 다음 단계 진입 → 실패 시 보상으로 역전.
 *
 * <p><b>한계 (다음 PR 에서 보강)</b>
 * <ul>
 *   <li>T2 (trade.confirm()) 실패 시 환불 보상 미구현 — 단순 throw + log warning. 운영자 수동 환불.
 *       Outbox 패턴 합류 시 T2 실패도 자동 보상.</li>
 *   <li>T1' (trade.cancel) 자체 실패 시도 — 운영자 수동 처리 필요. Outbox 합류 후 재시도 큐로.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeSagaService {

    private final TradeRepository tradeRepository;
    private final TradeService tradeService;
    private final PaymentService paymentService;

    /**
     * 거래 확정 Saga.
     *
     * @throws BusinessException {@link ErrorCode#TRADE_NOT_FOUND}
     * @throws BusinessException {@link ErrorCode#NOT_PRODUCT_OWNER} (buyer 불일치)
     * @throws BusinessException {@link ErrorCode#INVALID_TRADE_TRANSITION} (RESERVED 아님)
     * @throws BusinessException {@link ErrorCode#PAYMENT_FAILED} (PG 응답 실패 → trade.cancel 보상 후)
     * @throws BusinessException {@link ErrorCode#PAYMENT_ALREADY_PROCESSED} (재시도 차단)
     */
    public TradeConfirmResponse confirm(Long buyerId, Long tradeId) {
        // ----- T0: 사전 validate (read-only, 자기 트랜잭션) -----
        Trade trade = loadAndValidateBuyer(tradeId, buyerId);

        // ----- T1: payment.charge — 자기 트랜잭션, 결과 영속 -----
        PaymentResponse payment = paymentService.charge(trade);

        if (payment.status() == PaymentStatus.PAID) {
            // ----- T2: trade.confirm — 자기 트랜잭션 -----
            try {
                TradeResponse confirmed = tradeService.confirm(buyerId, tradeId);
                log.info("[saga.confirm] OK tradeId={} paymentId={} txId={}",
                        tradeId, payment.id(), payment.gatewayTxId());
                return new TradeConfirmResponse(confirmed, payment);
            } catch (BusinessException ex) {
                // 한계: 환불 보상 미구현 — 운영자 수동 처리. 본 PR 범위 밖.
                log.error("[saga.confirm] T2 실패 — 환불 보상 필요. tradeId={} paymentId={} txId={}",
                        tradeId, payment.id(), payment.gatewayTxId(), ex);
                throw ex;
            }
        } else {
            // ----- T1' compensate: trade.cancel — 자기 트랜잭션, Product 복원 -----
            log.warn("[saga.confirm] T1 실패 → T1' 보상 시작. tradeId={} paymentId={}",
                    tradeId, payment.id());
            try {
                tradeService.cancel(buyerId, tradeId);
            } catch (BusinessException compensationEx) {
                log.error("[saga.confirm] T1' 보상 실패 — 운영자 개입 필요. tradeId={}", tradeId, compensationEx);
                // 보상 실패도 PAYMENT_FAILED 로 통일 — 사용자 입장에선 결제 실패가 일차 원인
            }
            throw new BusinessException(ErrorCode.PAYMENT_FAILED);
        }
    }

    @Transactional(readOnly = true)
    protected Trade loadAndValidateBuyer(Long tradeId, Long buyerId) {
        Trade trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
        if (!trade.isBuyer(buyerId)) {
            throw new BusinessException(ErrorCode.NOT_PRODUCT_OWNER);
        }
        return trade;
    }
}
