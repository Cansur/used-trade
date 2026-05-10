package com.portfolio.used_trade.payment.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.payment.domain.Payment;
import com.portfolio.used_trade.payment.dto.PaymentResponse;
import com.portfolio.used_trade.payment.gateway.PaymentGatewayPort;
import com.portfolio.used_trade.payment.repository.PaymentRepository;
import com.portfolio.used_trade.trade.domain.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 도메인 서비스 — Saga 의 T1 단계.
 *
 * <p>책임:
 * <ul>
 *   <li>{@link #charge(Trade)} — Mock PG 호출 + Payment row 영속화 (PAID 또는 FAILED).
 *       Saga {@link com.portfolio.used_trade.trade.service.TradeSagaService} 가 호출.</li>
 *   <li>중복 결제 차단 — 같은 trade 의 Payment 이미 있으면 {@link ErrorCode#PAYMENT_ALREADY_PROCESSED}.</li>
 * </ul>
 *
 * <p><b>왜 Saga 가 직접 PG 부르지 않고 PaymentService 를 거치는가?</b>
 * Payment row 영속화는 PG 호출 결과와 묶여야 한다 (감사/재시도/환불의 근거). 도메인 (Payment) 의
 * 상태 전이를 한 곳에서 책임지도록 PaymentService 가 PG + 영속화 둘 다 담당.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGatewayPort paymentGateway;

    /**
     * 결제 시도. PG 응답에 따라 Payment 가 PAID 또는 FAILED 로 영속화된다.
     *
     * <p><b>Saga 패턴의 책임 분리</b>: 본 메서드는 자기 트랜잭션 안에서 PG 호출 결과를
     * 영속화하고 그 결과를 반환만 한다. 실패 (FAILED) 인 경우에도 BusinessException 을
     * throw 하지 않는다 — 그러면 본 트랜잭션이 롤백되어 FAILED row 자체가 사라지기 때문.
     * 호출자 Saga 가 응답의 {@link PaymentResponse#status()} 를 확인해 보상 트리거.
     *
     * @throws BusinessException {@link ErrorCode#PAYMENT_ALREADY_PROCESSED} 같은 trade 재결제 (이건 PG 호출 전 가드라 롤백 OK)
     */
    @Transactional
    public PaymentResponse charge(Trade trade) {
        if (paymentRepository.existsByTradeId(trade.getId())) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        // PENDING 으로 먼저 영속화 — PG 호출 도중 서버 죽으면 PENDING 으로 흔적 남김 (운영 추적)
        Payment payment = paymentRepository.save(Payment.initiate(trade, trade.getPricePaid()));

        PaymentGatewayPort.Result result = paymentGateway.charge(trade.getId(), trade.getPricePaid());
        if (result.success()) {
            payment.markPaid(result.gatewayTxId());
            log.info("[payment.charge] tradeId={} -> PAID gatewayTxId={}", trade.getId(), result.gatewayTxId());
        } else {
            payment.markFailed();
            log.warn("[payment.charge] tradeId={} -> FAILED reason={}", trade.getId(), result.failureReason());
        }
        // FAILED 든 PAID 든 row 영속화 후 응답 반환 — Saga 가 status 로 분기.
        return PaymentResponse.from(payment);
    }
}
