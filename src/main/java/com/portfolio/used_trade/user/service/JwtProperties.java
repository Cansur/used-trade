package com.portfolio.used_trade.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jwt.*} 프로퍼티 바인딩.
 *
 * <p><b>왜 record + {@link ConfigurationProperties} 인가?</b>
 * <ul>
 *   <li>불변 — 부팅 시 한 번 바인딩되면 절대 안 바뀜</li>
 *   <li>{@code @Value("${...}")} 산재보다 한 곳에서 묶어서 관리</li>
 *   <li>타입 안전 — 잘못된 타입은 부팅 시점에 실패</li>
 * </ul>
 *
 * <p>스캔은 {@code @ConfigurationPropertiesScan} (UsedTradeApplication) 으로 활성화.
 *
 * @param secret                 HS256 서명 비밀키. <b>32바이트 이상</b> (jjwt 0.12+ 가 짧으면 거부).
 * @param accessTokenValidityMs  Access Token 유효 시간 (ms). 운영 기준 30분 (1,800,000ms).
 * @param refreshTokenValidityMs Refresh Token 유효 시간 (ms). 운영 기준 14일.
 * @param issuer                 토큰의 {@code iss} 클레임 — 발행자 식별.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        long accessTokenValidityMs,
        long refreshTokenValidityMs,
        String issuer
) {
}
