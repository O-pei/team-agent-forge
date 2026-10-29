package com.agentforge.security.auth;

import java.util.Set;

/**
 * JWT 认证后放入 SecurityContext 的当前用户（跨模块 DTO）。
 *
 * <p>由 {@code JwtAuthenticationFilter} 构造；其它模块通过
 * {@link com.agentforge.security.util.SecurityUtils} 读取，不要直接依赖本类之外的安全内部结构。</p>
 */
public record AuthenticatedUser(
        Long userId,
        String username,
        Long tenantId,
        Set<String> roles,
        Set<String> permissions) {

    public boolean hasPermission(String code) {
        return permissions != null && permissions.contains(code);
    }
}
