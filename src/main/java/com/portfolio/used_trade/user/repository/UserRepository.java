package com.portfolio.used_trade.user.repository;

import com.portfolio.used_trade.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 사용자 영속성 어댑터.
 *
 * <p>Spring Data JPA 가 부팅 시점에 메서드 이름을 파싱해 자동으로 구현체를
 * 생성·Bean 등록한다. 우리는 인터페이스만 두면 됨.
 *
 * <p>{@link JpaRepository} 가 기본 제공:
 * {@code save(), findById(), findAll(), deleteById(), count(), existsById()} 등.
 *
 * <p>아래 두 메서드는 우리 도메인이 자주 쓰는 조회를 명시적으로 선언:
 * <ul>
 *   <li>{@link #findByEmail(String)} — 로그인 시 사용자 조회</li>
 *   <li>{@link #existsByEmail(String)} — 가입 중복 검사 (전체 row 안 가져와서 빠름)</li>
 * </ul>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
