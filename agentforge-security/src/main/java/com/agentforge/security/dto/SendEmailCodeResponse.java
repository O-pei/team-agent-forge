package com.agentforge.security.dto;

/**
 * 发送邮箱验证码响应。
 *
 * @param email         归一化后的邮箱
 * @param scene         场景
 * @param expiresIn     验证码有效期（秒）
 * @param cooldown      再次发送冷却（秒）
 * @param devCode       仅当 {@code agentforge.auth.expose-code-in-response=true} 时回显，便于本地演示
 */
public record SendEmailCodeResponse(
        String email,
        String scene,
        long expiresIn,
        long cooldown,
        String devCode) {
}
