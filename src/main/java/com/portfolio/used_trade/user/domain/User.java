package com.portfolio.used_trade.user.domain;

import com.portfolio.used_trade.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 엔티티.
 *
 * <p><b>설계 원칙</b>
 * <ul>
 *   <li>setter 미노출 — 상태 변경은 의도 있는 메서드로만 노출
 *       ({@link #changeNickname(String)}, {@link #softDelete()}, {@link #suspend()})</li>
 *   <li>이메일은 가입 후 변경 불가 — 변경 메서드 자체를 두지 않음</li>
 *   <li>비밀번호는 항상 BCrypt 해시 상태로 들어옴 — 평문이 들어오는 일은 없도록
 *       서비스 레이어가 책임</li>
 *   <li>탈퇴는 row 삭제 X — {@link UserStatus#DELETED} 로 soft delete</li>
 * </ul>
 *
 * <p><b>인덱스</b>
 * <ul>
 *   <li>{@code uk_users_email} : email UNIQUE — 가입 중복 + 로그인 조회 + race condition 방어</li>
 * </ul>
 *
 * <p><b>JPA 요구사항</b>
 * <ul>
 *   <li>기본 생성자 필요 — Hibernate 가 reflection 으로 인스턴스 생성 시 사용.
 *       외부에서 직접 호출하지 못하게 {@code PROTECTED} 로 가둠</li>
 *   <li>{@link BaseEntity} 상속 — createdAt / updatedAt 자동 주입</li>
 * </ul>
 */
@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "uk_users_email", columnList = "email", unique = true)
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // MySQL AUTO_INCREMENT 활용
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    /** BCrypt 해시 — 항상 정확히 60자. */
    @Column(nullable = false, length = 60)
    private String password;

    @Column(nullable = false, length = 20)
    private String nickname;

    /** {@link Role} — STRING 으로 저장 (ORDINAL 의 순서 의존성 회피). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    // ---------- 생성 ----------

    /**
     * 회원가입 전용 정적 팩토리.
     *
     * @param email           검증된 이메일
     * @param encodedPassword BCrypt 로 해시된 비밀번호 (서비스 레이어 책임)
     * @param nickname        검증된 닉네임
     */
    public static User create(String email, String encodedPassword, String nickname) {
        User user = new User();
        user.email = email;
        user.password = encodedPassword;
        user.nickname = nickname;
        user.role = Role.USER;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    // ---------- 도메인 동작 (의도 있는 setter 대체) ----------

    /** 닉네임 변경. 비즈니스 규칙(검증) 은 서비스에서. */
    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    /** 비밀번호 변경. {@code encodedPassword} 는 이미 BCrypt 해시여야 함. */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    /** 탈퇴 (soft delete). row 는 남고 상태만 전이. */
    public void softDelete() {
        this.status = UserStatus.DELETED;
    }

    /** 운영자에 의한 정지. */
    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }

    /** 정지 해제 → 다시 활성. */
    public void activate() {
        this.status = UserStatus.ACTIVE;
    }

    // ---------- 상태 질의 헬퍼 ----------

    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }
}
