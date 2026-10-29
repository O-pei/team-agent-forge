package com.agentforge.security.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（契约 §2.1 POST /api/v1/auth/login）：account 支持邮箱或用户名。
 */
public record LoginRequest(
        @NotBlank(message = "账号不能为空")
        String account,

        @NotBlank(message = "密码不能为空")
        String password) {
}
