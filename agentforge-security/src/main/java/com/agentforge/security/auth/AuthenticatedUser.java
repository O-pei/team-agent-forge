package com.agentforge.security.auth;

import java.util.Set;

/**
 * JWT 认证后放入 SecurityContext 的当前用户，其它模块通过 SecurityUtils 读取。
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
