package com.portfolio.used_trade.trade.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.retry.annotation.RetryConfiguration;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Retryable} 이 {@code @Transactional} 바깥에서 동작하는지 고정한다.
 *
 * <p>이 순서는 코드에 명시돼 있지 않고 {@code @EnableRetry} 의 기본 order 에 기댄다.
 * 순서가 뒤집히면 재시도가 실패한 트랜잭션 안에서 일어나고, 커밋 시점 충돌은 재시도되지 않는다
 * (ADR-2 에러 상황 3). {@code TradeReserveConcurrencyTest} 는 뒤집혀도 통과할 수 있어 별도로 둔다.
 */
@SpringBootTest
@DisplayName("TradeService advice 순서")
class TradeServiceAdviceOrderTest {

    @Autowired
    private TradeService tradeService;

    @Test
    @DisplayName("Retry advisor 가 트랜잭션 advisor 보다 바깥이다")
    void retryAdvisor_wrapsTransactionAdvisor() {
        List<Advisor> advisors = List.of(((Advised) tradeService).getAdvisors());

        int retry = indexOf(advisors, RetryConfiguration.class);
        int transaction = indexOf(advisors, BeanFactoryTransactionAttributeSourceAdvisor.class);

        assertThat(retry).as("Retry advisor 존재").isNotNegative();
        assertThat(transaction).as("트랜잭션 advisor 존재").isNotNegative();
        assertThat(retry).as("체인 앞쪽이 바깥 — %s", advisors).isLessThan(transaction);
    }

    private int indexOf(List<Advisor> advisors, Class<?> type) {
        for (int i = 0; i < advisors.size(); i++) {
            if (type.isInstance(advisors.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
