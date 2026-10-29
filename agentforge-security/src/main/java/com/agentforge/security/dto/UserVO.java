package com.agentforge.security.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户管理视图（契约 §2.2）。
 */
public record UserVO(
        Long id,
        String username,
        String email,
        String nickname,
        String avatarUrl,
        String phone,
        String status,
        boolean emailVerified,
        List<String> roles,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt) {
}
