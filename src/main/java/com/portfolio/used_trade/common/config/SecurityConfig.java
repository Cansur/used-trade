package com.portfolio.used_trade.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 최소 설정 (Phase 1).
 *
 * <p><b>현재 상태</b>: 모든 경로 permitAll — HelloController 검증 및
 * 초기 개발 편의를 위해 인증을 열어둔다.
 *
 * <p><b>Phase 2 TODO</b>
 * <ul>
 *   <li>JWT 필터 추가 ({@code JwtAuthenticationFilter})</li>
 *   <li>{@code /api/auth/**} 만 permitAll, 나머지는 authenticated</li>
 *   <li>{@code UserDetailsService} 구현 (user 도메인에서)</li>
 *   <li>CORS 허용 오리진 화이트리스트 (React dev 서버)</li>
 * </ul>
 *
 * <p><b>REST API 의 일반적인 설계 선택</b>
 * <ul>
 *   <li>{@code csrf.disable()} — REST API 는 쿠키가 아니라 Authorization 헤더
 *       (JWT) 를 쓰므로 CSRF 가 불필요. 단 쿠키 기반 세션을 쓴다면 반드시 켤 것.</li>
 *   <li>{@code httpBasic.disable()}, {@code formLogin.disable()} —
 *       Spring Security 가 기본으로 띄우는 로그인 폼/BasicAuth 팝업 끄기.</li>
 *   <li>{@code SessionCreationPolicy.STATELESS} — JWT 를 쓸 것이므로 세션 미사용.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Phase 1: 모든 경로 허용. Phase 2 에서 JWT 필터 + 권한 체계로 교체.
                        .anyRequest().permitAll()
                )
                .build();
    }

    /**
     * 비밀번호 해싱에 BCrypt 채택.
     * <ul>
     *   <li>Salt 가 해시 안에 포함되어 별도 저장 불필요</li>
     *   <li>강도(work factor) 조정 가능 — 기본 10, 더 안전하려면 12</li>
     * </ul>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
