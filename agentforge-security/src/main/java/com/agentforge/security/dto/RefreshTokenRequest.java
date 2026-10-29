package com.agentforge.security.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新 token 请求与登出请求（refreshToken 可选）。
 */
public record RefreshTokenRequest(
        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}
