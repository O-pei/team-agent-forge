package com.agentforge.security.util;

import com.agentforge.common.api.ErrorCode;
import com.agentforge.common.exception.BizException;
import com.agentforge.security.auth.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

/**
 * 当前登录用户访问入口（跨模块使用）。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof AuthenticatedUser user ? user : null;
    }

    public static Long currentUserId() {
        AuthenticatedUser user = currentUser();
        return user == null ? null : user.userId();
    }

    public static Long currentUserIdOrThrow() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    public static Long currentTenantId() {
        AuthenticatedUser user = currentUser();
        return user == null || user.tenantId() == null ? 1L : user.tenantId();
    }

    public static Set<String> currentRoles() {
        AuthenticatedUser user = currentUser();
        return user == null ? Set.of() : user.roles();
    }

    public static boolean hasPermission(String code) {
        AuthenticatedUser user = currentUser();
        return user != null && user.hasPermission(code);
    }
}
