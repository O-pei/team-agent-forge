package com.agentforge.security.dto;

/**
 * 发送邮箱验证码响应：有效期与冷却秒数，devCode 仅在演示开关开启时回显。
 */
public record SendEmailCodeResponse(
        String email,
        String scene,
        long expiresIn,
        long cooldown,
        String devCode) {
}
