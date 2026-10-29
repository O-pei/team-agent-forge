package com.agentforge.security.dto;

import java.util.List;

/**
 * 登录 / 刷新成功响应（契约 §2.1）。
 *
 * @param accessToken  access token（2h）
 * @param refreshToken refresh token（7d，刷新后旧值失效）
 * @param expiresIn    access token 有效期（秒）
 * @param user         当前用户基础信息
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserInfo user) {
}
