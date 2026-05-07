package com.portfolio.used_trade.trade.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.product.domain.Product;
import com.portfolio.used_trade.product.repository.ProductRepository;
import com.portfolio.used_trade.trade.domain.Trade;
import com.portfolio.used_trade.trade.dto.TradeResponse;
import com.portfolio.used_trade.trade.repository.TradeRepository;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래 도메인 핵심 비즈니스 로직 — ADR-2 (낙관적 락 + Spring Retry) 의 진입점.
 *
 * <p>책임:
 * <ul>
 *   <li>예약(reserve) — 동일 상품에 대한 동시 요청 중 1명만 성공하도록 낙관적 락으로 직렬화</li>
 *   <li>존재 검증 — buyer / product</li>
 *   <li>도메인 가드 위임 — 본인 상품, 잘못된 상품 상태는 도메인 메서드가 throw</li>
 * </ul>
 *
 * <p><b>왜 {@code @Retryable + saveAndFlush} 인가?</b><br>
 * Product 의 {@code @Version} 으로 동시 예약 충돌은 검출되지만, 보통의 {@code save()} 는
 * 트랜잭션 커밋 시점에야 flush 한다. 그러면 {@link OptimisticLockingFailureException}
 * 이 메서드 <i>밖</i>에서 발생하고 {@code @Retryable} 이 잡지 못한다.
 * {@code saveAndFlush} 로 메서드 내부에서 즉시 flush → 충돌이 메서드 안에서 터짐 →
 * Spring Retry 가 재시도 가능. 이게 ADR-2 시연의 핵심.
 *
 * <p><b>재시도 정책</b>
 * <ul>
 *   <li>최대 3회 (초기 시도 1회 + 재시도 2회)</li>
 *   <li>지수 백오프 — 50ms → 100ms (multiplier 2)</li>
 *   <li>모두 실패하면 {@link #recoverReserve} 가 {@code TRADE_ALREADY_RESERVED} 로 변환</li>
 * </ul>
 *
 * <p><b>주의 — Self invocation</b><br>
 * {@code @Retryable} / {@code @Transactional} 은 AOP 프록시로 동작한다. 같은 클래스 내부의
 * 자기 호출(self invocation) 은 프록시를 거치지 않으므로 재시도/트랜잭션이 적용되지 않는다.
 * {@link #reserve(Long, Long)} 은 컨트롤러에서만 호출되어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeService {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final TradeRepository tradeRepository;

    /**
     * 거래 예약. 동시 요청 시 낙관적 락 충돌 → Spring Retry 가 재시도.
     *
     * @throws BusinessException {@link ErrorCode#USER_NOT_FOUND}            buyer 부재
     * @throws BusinessException {@link ErrorCode#PRODUCT_NOT_FOUND}         product 부재
     * @throws BusinessException {@link ErrorCode#TRADE_SELF_NOT_ALLOWED}    본인 상품 예약 시도
     * @throws BusinessException {@link ErrorCode#PRODUCT_NOT_AVAILABLE}     이미 TRADING/SOLD
     * @throws BusinessException {@link ErrorCode#TRADE_ALREADY_RESERVED}    재시도 모두 실패 (recover)
     */
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2)
    )
    @Transactional
    public TradeResponse reserve(Long buyerId, Long productId) {
        User buyer = userRepository.findById(buyerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        // Trade.reserve() 정적 팩토리가 다음을 한 번에 처리:
        //   - 본인 상품 차단 (TRADE_SELF_NOT_ALLOWED)
        //   - Product.reserve() 호출 (PRODUCT_NOT_AVAILABLE 가드)
        //   - 가격 스냅샷 + RESERVED 로 생성
        Trade trade = Trade.reserve(product, buyer);

        // saveAndFlush 로 즉시 flush — Product 의 @Version 충돌이 이 메서드 안에서 터지도록 강제.
        // 충돌 시 OptimisticLockingFailureException → @Retryable 이 캡처해 재시도.
        Trade saved = tradeRepository.saveAndFlush(trade);
        return TradeResponse.from(saved);
    }

    /**
     * 재시도 모두 실패 시 호출되는 복구 핸들러.
     * 사용자에게는 "이미 예약된 상품" 으로 응답 — 동시 요청 중 다른 구매자가 선점했다는 의미.
     *
     * <p>{@code @Recover} 의 메서드 시그니처는 첫 인자가 catch 타입(예외) 이고,
     * 그 뒤에 원본 메서드의 인자들이 차례로 와야 한다.
     */
    @Recover
    public TradeResponse recoverReserve(OptimisticLockingFailureException ex,
                                        Long buyerId, Long productId) {
        log.warn("[trade.reserve] 낙관적 락 재시도 모두 실패 — buyerId={}, productId={}", buyerId, productId, ex);
        throw new BusinessException(ErrorCode.TRADE_ALREADY_RESERVED);
    }

    /**
     * BusinessException 용 @Recover.
     *
     * <p><b>왜 필요한가?</b><br>
     * Spring Retry 는 {@code @Retryable} 메서드에서 발생한 예외가 {@code retryFor} 에
     * 매칭되지 않으면 즉시 종료하고, 그 다음 단계로 {@code @Recover} 를 탐색한다.
     * 매칭되는 {@code @Recover} 를 못 찾으면 원본 예외를 그대로 던지는 게 아니라
     * {@code ExhaustedRetryException("Cannot locate recovery method")} 로 감싼다.
     * 즉 {@link BusinessException} 이 그대로 클라이언트로 도달하지 못하고
     * 500 INTERNAL_SERVER_ERROR 가 된다.
     *
     * <p>그래서 BusinessException 시그니처의 {@code @Recover} 를 두고 그대로 rethrow 하면,
     * Spring Retry 가 wrap 하지 않고 {@link com.portfolio.used_trade.common.exception.GlobalExceptionHandler}
     * 까지 BusinessException 그대로 도달한다 (PRODUCT_NOT_FOUND, TRADE_SELF_NOT_ALLOWED,
     * PRODUCT_NOT_AVAILABLE 등이 의도한 응답 코드로 변환됨).
     */
    @Recover
    public TradeResponse rethrowBusinessException(BusinessException ex,
                                                  Long buyerId, Long productId) {
        throw ex;
    }
}
