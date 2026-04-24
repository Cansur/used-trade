package com.portfolio.used_trade.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 관련 전역 Config.
 *
 * <p>{@link EnableJpaAuditing} — {@code @CreatedDate}, {@code @LastModifiedDate}
 * 를 작동시키는 스위치. 이 설정이 빠지면 BaseEntity 의 시각 필드가
 * 모두 null 로 저장되어 디버깅이 까다로워진다.
 *
 * <p>향후 확장 예정:
 * <ul>
 *   <li>{@code AuditorAware<Long>} — 생성자/수정자(userId)를 Security Context
 *       에서 꺼내 자동 주입 (Phase 2)</li>
 * </ul>
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
