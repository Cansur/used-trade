package com.portfolio.used_trade.user.dto;

/**
 * 토큰 응답 DTO.
 *
 * <p>로그인 시 두 토큰 모두 발급, refresh 시 access 만 재발급.
 * 두 케이스에서 응답 형식을 통일하기 위해 같은 record 를 사용하고,
 * refresh 응답에선 {@code refreshToken} 이 {@code null} 로 들어간다.
 * Jackson 의 전역 설정 {@code default-property-inclusion: non_null} 덕분에
 * null 필드는 응답 JSON 에서 자동으로 제외됨.
 *
 * @param accessToken          새 Access Token
 * @param refreshToken         새 Refresh Token (refresh API 응답에선 null)
 * @param accessTokenExpiresIn Access Token 만료까지 남은 초 (프론트가 만료 추적용)
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresIn
) {

    /** 로그인 시 — 두 토큰 모두 발급. */
    public static TokenResponse of(String accessToken, String refreshToken, long expiresIn) {
        return new TokenResponse(accessToken, refreshToken, expiresIn);
    }

    /** Refresh 시 — Access 만 재발급. */
    public static TokenResponse accessOnly(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, null, expiresIn);
    }
}
