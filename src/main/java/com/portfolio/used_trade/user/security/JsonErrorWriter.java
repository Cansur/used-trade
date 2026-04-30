package com.portfolio.used_trade.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Servlet 응답에 표준 {@link ApiResponse} JSON 을 직접 쓰는 작은 유틸.
 *
 * <p><b>왜 필요한가?</b>
 * 보안 관련 에러는 두 군데서 발생한다 — {@link JwtAuthenticationFilter} 와
 * SecurityConfig 의 {@code AuthenticationEntryPoint / AccessDeniedHandler}.
 * 둘 다 DispatcherServlet 앞 단계라 {@code @ControllerAdvice} 가 못 잡으므로
 * 직접 JSON 을 써야 한다. 같은 5줄을 두 군데 쓰지 않도록 한 곳에 모아두는 것.
 *
 * <p>응답 envelope 은 {@link ApiResponse#error(String, String)} 으로 통일 →
 * 클라이언트는 모든 실패에서 같은 형태를 받는다 (필드 분기 일관).
 */
public final class JsonErrorWriter {

    private JsonErrorWriter() {
        // 유틸 클래스 인스턴스화 방지
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode code)
            throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body = ApiResponse.error(code.getCode(), code.getDefaultMessage());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
