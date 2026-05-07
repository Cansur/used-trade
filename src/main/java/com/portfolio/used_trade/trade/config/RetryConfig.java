package com.portfolio.used_trade.trade.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Spring Retry 활성화 — ADR-2 (낙관적 락 + 재시도) 의 인프라 토대.
 *
 * <p>{@link EnableRetry} 가 있어야 {@code @Retryable} / {@code @Recover} 가
 * AOP 프록시 기반으로 동작한다. 이 어노테이션이 없으면 메서드 호출은 그냥 일반 호출이고
 * 재시도가 일어나지 않는다.
 *
 * <p>전역 활성화이지만 trade 도메인에 두는 이유: 현재 retry 가 필요한 유일한 책임이
 * trade 의 동시 예약 충돌이고, 이 결정의 근거가 ADR-2 에 묶여 있기 때문이다.
 * 다른 도메인이 retry 를 요구하게 되면 common/config 로 승격을 검토.
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
