package com.portfolio.used_trade.saga.controller;

import com.portfolio.used_trade.common.response.ApiResponse;
import com.portfolio.used_trade.saga.dto.TradeConfirmResponse;
import com.portfolio.used_trade.saga.service.TradeSagaService;
import com.portfolio.used_trade.user.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 거래 확정 REST API. 패키지만 saga 이고 URL 은 거래 자원 아래에 그대로 둔다.
 */
@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeSagaController {

    private final TradeSagaService tradeSagaService;

    /**
     * 거래 확정 (결제 진행) — Saga.
     *
     * <p>흐름:
     * <ol>
     *   <li>buyer 본인 검증 + RESERVED 상태 검증</li>
     *   <li>Mock PG 결제 시도</li>
     *   <li>성공 → trade.confirm() (CONFIRMED)</li>
     *   <li>실패 → trade.cancel() 보상 (Product 복원) + 402 PAYMENT_FAILED</li>
     * </ol>
     */
    @PostMapping("/{tradeId}/confirm")
    public ApiResponse<TradeConfirmResponse> confirm(
            @AuthenticationPrincipal AuthUser auth,
            @PathVariable Long tradeId
    ) {
        return ApiResponse.success(tradeSagaService.confirm(auth.id(), tradeId));
    }
}
