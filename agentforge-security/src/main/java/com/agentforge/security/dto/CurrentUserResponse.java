package com.agentforge.security.dto;

import java.util.List;
import java.util.Set;

/**
 * 当前用户信息（契约 §2.1 GET /api/v1/auth/me）。
 */
public record CurrentUserResponse(
        UserInfo user,
        Set<String> roles,
        Set<String> permissions,
        List<MenuItem> menus) {
}
