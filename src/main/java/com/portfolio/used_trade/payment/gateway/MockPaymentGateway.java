package com.portfolio.used_trade.payment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 데모용 Mock 결제 게이트웨이. 항상 성공 — 운영용 PG (Toss / 카카오페이 / 아임포트 등) 어댑터로
 * 교체 가능.
 *
 * <p>{@code @Profile("!prod")} — 운영 환경에선 빈으로 등록되지 않음. 운영 어댑터를 별도로 박아야 함.
 *
 * <p><b>실패 시뮬레이션</b> — 통합 테스트의 Saga 보상 시나리오는 별도 ControllablePaymentGateway
 * (테스트 전용) 로 강제 실패 주입한다. 본 클래스는 단순화 위해 항상 성공.
 */
@Slf4j
@Component
@Profile("!prod")
public class MockPaymentGateway implements PaymentGatewayPort {

    @Override
    public Result charge(Long tradeId, Long amountKrw) {
        String gatewayTxId = "MOCK-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        log.info("[mock-pg.charge] tradeId={} amount={} -> {}", tradeId, amountKrw, gatewayTxId);
        return Result.success(gatewayTxId);
    }

    @Override
    public Result refund(String gatewayTxId) {
        log.info("[mock-pg.refund] gatewayTxId={}", gatewayTxId);
        return Result.success(gatewayTxId);
    }
}
