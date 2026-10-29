package com.agentforge.common.context;

import java.util.Set;

/**
 * 当前认证用户上下文。由 JWT 过滤器在请求线程内填充，供审计 / 业务层读取。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static Long getUserIdOrNull() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public record CurrentUser(Long userId, String username, Long tenantId, Set<String> roles) {
    }
}
