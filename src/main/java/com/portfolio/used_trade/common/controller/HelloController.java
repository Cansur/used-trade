package com.portfolio.used_trade.common.controller;

import com.portfolio.used_trade.common.response.ApiResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 초기 환경 검증용 엔드포인트.
 *
 * <p>목적:
 * <ul>
 *   <li>Spring Boot 기동 확인 ({@code GET /api/hello})</li>
 *   <li>MySQL / Redis 연결 확인 ({@code GET /api/hello/health-db})</li>
 *   <li>공통 응답 포맷({@link ApiResponse}) 동작 확인</li>
 * </ul>
 *
 * <p>도메인 개발이 시작되면 이 컨트롤러는 제거하거나
 * {@code /actuator/health} 만 남기고 교체할 예정.
 */
@RestController
@RequestMapping("/api/hello")
public class HelloController {

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;

    // 생성자 주입 — 필드 주입(@Autowired) 대신 권장되는 방식:
    //  1) 불변(final) 선언 가능 → 런타임 재바인딩 방지
    //  2) 테스트 시 Mock 주입이 명시적
    //  3) 순환 의존을 컴파일/기동 시점에 바로 드러냄
    public HelloController(DataSource dataSource, StringRedisTemplate redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 가장 단순한 ping.
     */
    @GetMapping
    public ApiResponse<String> hello() {
        return ApiResponse.success("Hello, used-trade!");
    }

    /**
     * MySQL / Redis 가 실제로 살아있는지 최소 확인.
     *
     * <p>실무 수준 헬스체크는 Actuator 의 {@code /actuator/health} 를 쓰지만,
     * 초기 수동 검증에는 이렇게 직접 확인하는 편이 문제 원인을 찾기 쉽다.
     */
    @GetMapping("/health-db")
    public ApiResponse<Map<String, String>> healthDb() {
        Map<String, String> result = new LinkedHashMap<>();

        // MySQL 연결 검증 — isValid(1) : 1초 타임아웃으로 SELECT 1 수행
        try (Connection conn = dataSource.getConnection()) {
            boolean ok = conn.isValid(1);
            result.put("mysql", ok ? "UP" : "DOWN");
        } catch (Exception e) {
            result.put("mysql", "DOWN: " + e.getMessage());
        }

        // Redis 연결 검증 — PING 명령으로 응답 확인
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            result.put("redis", "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN");
        } catch (Exception e) {
            result.put("redis", "DOWN: " + e.getMessage());
        }

        return ApiResponse.success(result);
    }
}
