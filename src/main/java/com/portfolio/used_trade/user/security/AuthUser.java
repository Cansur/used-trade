package com.portfolio.used_trade.user.security;

import com.portfolio.used_trade.user.domain.Role;
import io.jsonwebtoken.Claims;

/**
 * 인증된 요청의 컨텍스트(누가 요청했는가)를 담는 불변 값.
 *
 * <p><b>역할</b>
 * <ul>
 *   <li>{@code JwtAuthenticationFilter} 가 토큰 검증 후 SecurityContext 의 principal 로 등록</li>
 *   <li>컨트롤러는 {@code @AuthenticationPrincipal AuthUser auth} 로 즉시 수령</li>
 * </ul>
 *
 * <p><b>왜 record 인가?</b>
 * <ul>
 *   <li>요청 처리 도중 변경될 일 없는 read-only 데이터 → 불변 자료구조가 자연</li>
 *   <li>equals/hashCode/toString 자동 — 디버깅/로그 편함</li>
 * </ul>
 *
 * <p><b>왜 토큰에서 직접 만들고 DB 조회를 안 하나?</b>
 * Stateless JWT 의 핵심 이점 — 인증 요청마다 {@code users} 테이블 조회를 면제.
 * 토큰 자체에 담긴 클레임만 신뢰해 응답/권한 검사를 처리한다. 닉네임 등 토큰에
 * 안 담긴 필드가 필요하면 그때만 DB 조회.
 *
 * <p><b>staleness 한계</b>
 * 권한 강등 같은 즉시성 요구는 {@code BlacklistService} 로 jti 차단 + Refresh 폐기 →
 * 재로그인 강제로 흡수.
 */
public record AuthUser(Long id, String email, Role role) {

    /**
     * 검증된 {@link Claims} 로부터 AuthUser 를 만든다.
     *
     * <p>호출 시점에 토큰 서명/만료/블랙리스트 검사가 이미 통과했다고 가정 —
     * 여기서는 클레임 구조만 신뢰.
     */
    public static AuthUser from(Claims claims) {
        Long id = Long.parseLong(claims.getSubject());
        String email = claims.get("email", String.class);
        Role role = Role.valueOf(claims.get("role", String.class));
        return new AuthUser(id, email, role);
    }
}
