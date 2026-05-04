package com.portfolio.used_trade.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.used_trade.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 필터 체인 + 비밀번호 인코더 빈 정의.
 *
 * <p><b>왜 user 도메인 패키지에?</b>
 * <ul>
 *   <li>{@link JwtAuthenticationFilter} 는 user/security 에 있고, SecurityConfig 가
 *       그것에 의존 → "common → 도메인 ❌" 규칙 위반 회피를 위해 user 안으로 이동</li>
 *   <li>{@link PasswordEncoder} 도 user 가 사실상 단독 소비 (UserService/AuthService) →
 *       user 가 소유하는 게 자연스럽다</li>
 * </ul>
 *
 * <p><b>권한 매트릭스</b>
 * <table border="1">
 *   <tr><th>경로</th><th>정책</th></tr>
 *   <tr><td>POST /api/users</td><td>permitAll (가입)</td></tr>
 *   <tr><td>POST /api/auth/**</td><td>permitAll (로그인/리프레시)</td></tr>
 *   <tr><td>GET /actuator/health</td><td>permitAll</td></tr>
 *   <tr><td>그 외</td><td>authenticated()</td></tr>
 * </table>
 *
 * <p>Stateless JWT 이므로 세션은 끄고, CSRF 도 끈다 (쿠키가 아니라 Authorization 헤더 사용).
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/users").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // 임시 — 헬스체크/디버그 핑용. JWT 검증과 무관한 공개 엔드포인트.
                        .requestMatchers("/api/hello/**").permitAll()
                        // 상품 단건/목록 조회는 비로그인 노출. 등록/수정/삭제는 authenticated().
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .anyRequest().authenticated()
                )
                // 인증 미설정 → 401 / 권한 부족 → 403, 둘 다 우리 ApiResponse JSON 으로 통일.
                // (httpBasic 을 끈 상태에서 기본 EntryPoint 가 403 을 던지는 동작을 401 로 교정)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * 인증 자체가 없는 요청 (Bearer 헤더 없음 등) 에 대한 401 응답.
     */
    private static AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, ex) -> JsonErrorWriter.write(response, objectMapper, ErrorCode.UNAUTHORIZED);
    }

    /**
     * 인증은 됐으나 권한이 부족한 요청 (예: USER 가 ADMIN 전용 엔드포인트 호출) 에 대한 403 응답.
     */
    private static AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, ex) -> JsonErrorWriter.write(response, objectMapper, ErrorCode.FORBIDDEN);
    }

    /**
     * BCrypt 비밀번호 인코더.
     * <ul>
     *   <li>Salt 가 해시 안에 포함 → 별도 저장 불필요</li>
     *   <li>work factor 기본 10 — 32-bit 머신에서 ~100ms (적정)</li>
     * </ul>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
