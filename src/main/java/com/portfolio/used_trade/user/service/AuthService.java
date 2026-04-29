package com.portfolio.used_trade.user.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.dto.LoginRequest;
import com.portfolio.used_trade.user.dto.TokenResponse;
import com.portfolio.used_trade.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증/세션 라이프사이클 비즈니스 로직.
 *
 * <p><b>책임 분리</b>
 * <ul>
 *   <li>{@link UserService} — 회원 정보 그 자체 (가입 / 프로필)</li>
 *   <li><b>{@code AuthService}</b> — 로그인 / 로그아웃 / 토큰 재발급</li>
 * </ul>
 *
 * <p><b>로그인 보안 정책</b>
 * <ul>
 *   <li>미존재 이메일과 비번 불일치를 같은 코드 ({@link ErrorCode#INVALID_PASSWORD}) 로
 *       응답 — 사용자 열거(user enumeration) 공격 방지</li>
 *   <li>SUSPENDED / DELETED 계정은 비번이 맞아도 차단
 *       ({@link ErrorCode#INACTIVE_USER})</li>
 *   <li>같은 사용자가 다시 로그인하면 Redis 의 Refresh Token 이 덮어써져 자동 회전</li>
 * </ul>
 *
 * <p><b>왜 readOnly = true 가 기본인가?</b>
 * 로그인 자체는 DB 쓰기가 없다 (User 상태 갱신 안 함, Redis 저장만). 읽기 전용 트랜잭션을
 * 기본으로 두고, 향후 last-login 갱신 같은 쓰기 동작이 추가되면 메서드별로 재정의한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;

    /**
     * 이메일 + 비번 검증 → Access/Refresh 발급 → Refresh 를 Redis 에 저장.
     *
     * @throws BusinessException {@link ErrorCode#INVALID_PASSWORD} 미존재 이메일 또는 비번 불일치
     * @throws BusinessException {@link ErrorCode#INACTIVE_USER}    SUSPENDED / DELETED 계정
     */
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PASSWORD));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.INACTIVE_USER);
        }

        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(), user.getEmail(), user.getRole()
        );
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        refreshTokenService.save(user.getId(), refreshToken, jwtProperties.refreshTokenValidityMs());

        return TokenResponse.of(
                accessToken,
                refreshToken,
                jwtProperties.accessTokenValidityMs() / 1000   // 프론트가 보기 좋게 초 단위
        );
    }
}
