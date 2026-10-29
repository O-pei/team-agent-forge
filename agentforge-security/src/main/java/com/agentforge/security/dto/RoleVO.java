package com.agentforge.security.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色视图（契约 §2.3）。
 */
public record RoleVO(
        Long id,
        String code,
        String name,
        String description,
        boolean builtin,
        List<Long> permissionIds,
        LocalDateTime createdAt) {
}
