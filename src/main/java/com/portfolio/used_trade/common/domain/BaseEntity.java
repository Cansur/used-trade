package com.portfolio.used_trade.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 모든 Entity 가 상속해야 하는 공통 부모.
 *
 * <p><b>제공 필드</b>
 * <ul>
 *   <li>{@code createdAt} — row 생성 시각 (불변)</li>
 *   <li>{@code updatedAt} — row 마지막 수정 시각</li>
 * </ul>
 *
 * <p><b>핵심 어노테이션 설명</b>
 * <ul>
 *   <li>{@link MappedSuperclass} — 이 클래스는 테이블로 매핑되지 않지만,
 *       자식 Entity 의 테이블에 필드가 포함됨</li>
 *   <li>{@link EntityListeners}({@link AuditingEntityListener}) —
 *       JPA 라이프사이클 훅을 걸어 save/update 시 시각을 자동 주입</li>
 *   <li>{@link CreatedDate} / {@link LastModifiedDate} — Spring Data JPA 가 주입 대상 필드를 식별</li>
 * </ul>
 *
 * <p>Auditing 이 실제로 작동하려면 {@code @EnableJpaAuditing} 이 필요 —
 * {@code common.config.JpaConfig} 에서 전역 활성화.
 *
 * <p>의도적으로 {@code id} 필드는 여기에 두지 않는다. 이유:
 * <ul>
 *   <li>PK 전략은 도메인별로 다를 수 있음 (auto-increment Long vs ULID 등)</li>
 *   <li>상속보다 조합(composition) 이 유연 — 공통은 auditing 만 강제</li>
 * </ul>
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
