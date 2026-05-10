package com.portfolio.used_trade.trade.controller;

import com.portfolio.used_trade.common.response.ApiResponse;
import com.portfolio.used_trade.trade.dto.TradeConfirmResponse;
import com.portfolio.used_trade.trade.dto.TradeReserveRequest;
import com.portfolio.used_trade.trade.dto.TradeResponse;
import com.portfolio.used_trade.trade.service.TradeSagaService;
import com.portfolio.used_trade.trade.service.TradeService;
import com.portfolio.used_trade.user.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 거래 REST API.
 *
 * <p>현재 노출:
 * <ul>
 *   <li>POST /api/trades — 상품 예약 (인증 필요, 본인 상품 차단)</li>
 * </ul>
 *
 * <p>다음 PR 에서 추가:
 * <ul>
 *   <li>POST /api/trades/{id}/confirm — 판매자 거래 확정 (결제 합류 시점)</li>
 *   <li>POST /api/trades/{id}/settle  — 인수 완료</li>
 *   <li>POST /api/trades/{id}/cancel  — 거래 취소</li>
 *   <li>GET  /api/trades              — 내 거래 이력</li>
 * </ul>
 *
 * <p>Security: SecurityConfig 의 {@code anyRequest().authenticated()} 정책에 자연스럽게
 * 잡힌다 — Bearer 헤더 없으면 401.
 */
@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;
    private final TradeSagaService tradeSagaService;

    /**
     * 거래 예약. 동시 요청 시 낙관적 락 + Spring Retry 가 1명만 성공시킨다 (ADR-2).
     *
     * <p>buyerId 는 본문이 아닌 인증 컨텍스트 ({@link AuthUser}) 에서 가져온다 —
     * 다른 사람으로 위장 예약 차단.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TradeResponse> reserve(
            @AuthenticationPrincipal AuthUser auth,
            @Valid @RequestBody TradeReserveRequest request
    ) {
        return ApiResponse.success(tradeService.reserve(auth.id(), request.productId()));
    }

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
