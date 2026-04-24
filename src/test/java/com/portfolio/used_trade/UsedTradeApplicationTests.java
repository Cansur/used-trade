package com.portfolio.used_trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot 기본 스모크 테스트 자리.
 *
 * <p>Spring Initializr 가 생성한 {@code @SpringBootTest} + {@code contextLoads()}
 * 는 제거했다. 이유:
 * <ul>
 *   <li>전체 컨텍스트를 올리면서 실제 MySQL/Redis 에 연결을 시도 →
 *       CI 환경에서 즉시 실패</li>
 *   <li>진짜 가치 있는 통합 테스트는 Testcontainers 기반으로 도메인별로
 *       따로 작성하는 것이 맞음 (Phase 2)</li>
 * </ul>
 *
 * <p>이 파일은 {@code ./gradlew test} 가 최소 하나는 돌려야 하는 CI 관례를
 * 만족시키기 위한 placeholder. 첫 도메인 테스트가 들어오면 제거.
 */
class UsedTradeApplicationTests {

    @Test
    void placeholder() {
        assertTrue(true);
    }
}
