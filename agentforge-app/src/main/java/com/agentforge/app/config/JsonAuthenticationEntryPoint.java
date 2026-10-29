package com.agentforge.app.config;

import com.agentforge.common.api.ApiResponse;
import com.agentforge.security.filter.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证统一返回 {@link ApiResponse} JSON（401），而不是 Spring Security 默认的 HTML / 空 body。
 * <p>access token 过期时按契约附录 B 返回 40101，其余未认证场景返回 40100。</p>
 */
@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR);
        int code = 40100;
        String message = "未登录或登录已过期";
        if (attribute instanceof Integer errorCode) {
            code = errorCode;
            if (errorCode == 40101) {
                message = "access token 已过期，请使用 refresh token 刷新";
            } else if (errorCode == 40100) {
                message = "access token 无效，请重新登录";
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(code, message));
    }
}
