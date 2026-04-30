package com.portfolio.used_trade.user.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.dto.LoginRequest;
import com.portfolio.used_trade.user.dto.RefreshRequest;
import com.portfolio.used_trade.user.dto.TokenResponse;
import com.portfolio.used_trade.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final BlacklistService blacklistService;
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

    /**
     * Refresh Token 으로 새 Access Token 만 재발급.
     *
     * <p><b>회전(rotation) 안 함</b> — Redis 의 refresh 는 그대로 두고 access 만 교체.
     * 이유: 단일 디바이스 가정 + 동시 사용 시 race 회피 (자세한 내용은 ADR 참조).
     *
     * <p>검증 단계:
     * <ol>
     *   <li>서명/만료 확인 ({@link JwtTokenProvider#parseClaims})</li>
     *   <li>Redis 에 같은 사용자 키로 저장된 토큰과 정확히 일치하는지 확인 → 위조/회전옛것 차단</li>
     *   <li>현재 사용자 상태가 ACTIVE 인지 확인 → 운영 정지 즉시 반영</li>
     * </ol>
     *
     * @throws BusinessException {@link ErrorCode#INVALID_TOKEN} 토큰 위조 / Redis 미존재 / 불일치
     * @throws BusinessException {@link ErrorCode#EXPIRED_TOKEN} refresh 만료
     * @throws BusinessException {@link ErrorCode#USER_NOT_FOUND} 사용자 탈퇴
     * @throws BusinessException {@link ErrorCode#INACTIVE_USER}  사용자 정지/탈퇴 상태
     */
    public TokenResponse refresh(RefreshRequest request) {
        Claims claims = jwtTokenProvider.parseClaims(request.refreshToken());
        Long userId = Long.parseLong(claims.getSubject());

        String stored = refreshTokenService.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        if (!stored.equals(request.refreshToken())) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.INACTIVE_USER);
        }

        String newAccess = jwtTokenProvider.createAccessToken(
                user.getId(), user.getEmail(), user.getRole()
        );
        return TokenResponse.accessOnly(newAccess, jwtProperties.accessTokenValidityMs() / 1000);
    }

    /**
     * 로그아웃 — Access jti 블랙리스트 + Redis Refresh 삭제.
     *
     * <p><b>멱등 (idempotent)</b>: 어떤 입력이든 예외를 밖으로 던지지 않는다.
     * <ul>
     *   <li>토큰 헤더 없음 / 빈 값 → 그냥 통과 (이미 로그아웃된 상태로 간주)</li>
     *   <li>무효 / 만료 토큰 → 사용 불가 상태이므로 추가 처리 의미 없음 → 조용히 noop</li>
     *   <li>유효 토큰 → 두 단계 모두 수행해야 강제 로그아웃 완성:
     *       blacklist 만 하면 클라이언트가 refresh 로 재발급 받아 통과 가능</li>
     * </ul>
     *
     * <p>응답은 항상 200 OK — 사용자 입장에서 "로그아웃 버튼" 의 결과는 항상 성공이어야 함.
     */
    public void logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtTokenProvider.parseClaims(accessToken);
            Long userId = Long.parseLong(claims.getSubject());
            long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();

            blacklistService.blacklist(claims.getId(), remaining);
            refreshTokenService.delete(userId);
        } catch (BusinessException ex) {
            // 이미 무효한 토큰 — 블랙리스트에 올릴 가치 없고, sub 도 신뢰 못함.
            // 클라이언트는 어차피 재로그인 필요하므로 사용자 경험상 OK 응답을 유지.
            log.debug("logout called with invalid/expired token: {}", ex.getErrorCode());
        }
    }
}
