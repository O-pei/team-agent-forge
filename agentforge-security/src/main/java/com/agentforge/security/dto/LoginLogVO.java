package com.agentforge.security.dto;

import java.time.LocalDateTime;

/**
 * 登录日志视图（契约 §2.4 GET /api/v1/login-logs）。
 */
public record LoginLogVO(
        Long id,
        Long userId,
        String username,
        boolean success,
        String reason,
        String ip,
        String userAgent,
        LocalDateTime createdAt) {
}
