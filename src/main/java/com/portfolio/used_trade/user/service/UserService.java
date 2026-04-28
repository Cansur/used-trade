package com.portfolio.used_trade.user.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.dto.SignUpRequest;
import com.portfolio.used_trade.user.dto.UserResponse;
import com.portfolio.used_trade.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 관련 핵심 비즈니스 로직.
 *
 * <p>책임:
 * <ul>
 *   <li>회원가입 — 이메일 중복 검사 + 비밀번호 해싱 + 저장</li>
 *   <li>(이후) 내 정보 조회 / 닉네임 변경 / 탈퇴 등</li>
 * </ul>
 *
 * <p><b>왜 클래스에 {@code @Transactional(readOnly = true)} 를 걸고
 * 쓰기 메서드만 따로 풀어주나?</b>
 * <ul>
 *   <li>읽기 전용 트랜잭션 — Hibernate 가 dirty checking 을 건너뛰어 약간 빠름</li>
 *   <li>읽기/쓰기를 메서드 단위로 명시 → 의도가 명확</li>
 *   <li>실수로 readOnly 인 메서드에서 쓰기를 하면 즉시 예외</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 회원가입.
     *
     * @throws BusinessException {@link ErrorCode#EMAIL_ALREADY_EXISTS} 이미 가입된 이메일
     */
    @Transactional
    public UserResponse signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        User user = User.create(request.email(), encodedPassword, request.nickname());

        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }
}
