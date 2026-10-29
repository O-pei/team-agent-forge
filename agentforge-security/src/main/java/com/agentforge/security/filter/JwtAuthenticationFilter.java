package com.agentforge.security.filter;

import com.agentforge.common.context.TenantContext;
import com.agentforge.common.context.UserContext;
import com.agentforge.security.auth.AuthenticatedUser;
import com.agentforge.security.auth.JwtService;
import com.agentforge.security.auth.JwtService.AccessClaims;
import com.agentforge.security.util.RequestUtils;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 认证过滤器：解析 Authorization: Bearer，填充 SecurityContext / UserContext / TenantContext，
 * 无论成功失败都在 finally 清理 ThreadLocal（异步线程不复用请求上下文）。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 认证失败原因编码（供 EntryPoint 输出 40101/40100）。 */
    public static final String ATTR_AUTH_ERROR = "agentforge.auth.error";

    /** 解析后的 token claims 供 Controller 使用（如登出取 jti）。 */
    public static final String ATTR_ACCESS_CLAIMS = "agentforge.auth.claims";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = RequestUtils.bearerToken(request);
            if (token != null) {
                try {
                    AccessClaims claims = jwtService.parseAccessToken(token);
                    AuthenticatedUser principal = new AuthenticatedUser(
                            claims.userId(), claims.username(), claims.tenantId(),
                            claims.roles(), claims.permissions());
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    claims.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
                    claims.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    request.setAttribute(ATTR_ACCESS_CLAIMS, claims);
                    UserContext.set(new UserContext.CurrentUser(
                            claims.userId(), claims.username(), claims.tenantId(), claims.roles()));
                    TenantContext.setTenantId(claims.tenantId());
                } catch (ExpiredJwtException ex) {
                    request.setAttribute(ATTR_AUTH_ERROR, 40101);
                } catch (JwtException | IllegalArgumentException ex) {
                    request.setAttribute(ATTR_AUTH_ERROR, 40100);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
