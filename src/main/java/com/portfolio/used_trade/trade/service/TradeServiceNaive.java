package com.portfolio.used_trade.trade.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.trade.domain.TradeStatus;
import com.portfolio.used_trade.trade.dto.TradeResponse;
import com.portfolio.used_trade.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * <b>의도적으로 동시성 보호를 안 한</b> 거래 예약 — ADR-2 의 Before 측면.
 *
 * <p><b>왜 존재하는가?</b><br>
 * 통합 테스트에서 "@Version + Retry 가 없으면 동시 reserve 시 중복 거래가 발생한다"
 * 는 점을 코드로 입증하기 위해 둔다. 운영 환경 (prod) 에선 빈으로 등록되지 않도록
 * {@code @Profile("!prod")} 로 가드한다.
 *
 * <p><b>왜 JdbcTemplate 인가?</b><br>
 * Product 엔티티에는 이미 {@code @Version} 이 박혀 있어서 JPA 경로로는 자동으로
 * 옵티미스틱 락 검사가 일어난다. 이 메서드는 그 검사 자체를 우회해 "보호 없는" 상태를
 * 재현해야 하므로 JPA 가 아닌 native SQL 로 직접 UPDATE / INSERT 한다.
 *
 * <p><b>재현하는 동시성 결함</b>
 * <ol>
 *   <li>두 트랜잭션이 같은 product 를 SELECT — 둘 다 status=AVAILABLE 본다 (REPEATABLE READ snapshot)</li>
 *   <li>각각 status='TRADING' 으로 UPDATE — InnoDB 가 row lock 으로 직렬화하지만,
 *       <b>WHERE 절에 status 조건이 없으므로</b> 두 번째 UPDATE 도 결국 성공 (lost update)</li>
 *   <li>각각 trades 에 INSERT — <b>중복 거래 N건 발생</b></li>
 * </ol>
 *
 * <p>이게 정확히 ADR-2 가 막으려는 시나리오다. After 측 ({@link TradeService#reserve})
 * 의 통합 테스트와 정확히 비교 가능.
 */
@Service
@Profile("!prod")
@RequiredArgsConstructor
public class TradeServiceNaive {

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 보호 없는 reserve. ADR-2 Before 시연용.
     *
     * <p>의도적으로 누락된 것:
     * <ul>
     *   <li>{@code @Retryable} — 재시도 메커니즘 없음</li>
     *   <li>{@code @Version} 검사 — JdbcTemplate 직접 호출로 우회</li>
     *   <li>도메인 메서드의 상태 머신 가드 — SELECT 후 status check 만 (AVAILABLE 인지) 하고,
     *       UPDATE 는 무조건 status='TRADING'</li>
     * </ul>
     */
    @Transactional
    public TradeResponse reserveNaive(Long buyerId, Long productId) {
        if (!userRepository.existsById(buyerId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        Map<String, Object> product;
        try {
            product = jdbcTemplate.queryForMap(
                    "SELECT id, seller_id, price, status FROM products WHERE id = ?", productId);
        } catch (EmptyResultDataAccessException e) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        Long sellerId = ((Number) product.get("seller_id")).longValue();
        Long price = ((Number) product.get("price")).longValue();
        String status = (String) product.get("status");

        if (sellerId.equals(buyerId)) {
            throw new BusinessException(ErrorCode.TRADE_SELF_NOT_ALLOWED);
        }
        if (!"AVAILABLE".equals(status)) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }

        // 의도적: WHERE 절에 status 조건 없음 → lost update 가능.
        // 보호 있는 버전이라면 "WHERE id=? AND status='AVAILABLE'" 로 conditional update 하거나
        // @Version 으로 row 버전 일치 검사를 한다.
        jdbcTemplate.update("UPDATE products SET status = 'TRADING', version = COALESCE(version, 0) + 1 WHERE id = ?", productId);

        // 의도적: 중복 검사 없음. 같은 product 에 대해 여러 trades 가 동시에 INSERT 될 수 있다.
        jdbcTemplate.update(
                "INSERT INTO trades (created_at, updated_at, product_id, buyer_id, price_paid, status, version) " +
                        "VALUES (NOW(6), NOW(6), ?, ?, ?, 'RESERVED', 0)",
                productId, buyerId, price
        );

        // 응답은 최소 정보만 — 새로 생성된 id 추적은 시연 가치 낮으므로 0 반환.
        return new TradeResponse(0L, productId, buyerId, sellerId, price, TradeStatus.RESERVED, LocalDateTime.now());
    }
}
