package com.agentforge.security.dto;

import java.util.List;

/**
 * 登录或刷新成功响应：access token、refresh token、有效期（秒）与用户信息。
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserInfo user) {
}
