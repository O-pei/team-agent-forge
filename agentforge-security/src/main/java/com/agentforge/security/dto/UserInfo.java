package com.agentforge.security.dto;

/**
 * 用户基础信息（登录响应中的 user 字段）。
 */
public record UserInfo(
        Long id,
        String username,
        String email,
        String nickname,
        String avatarUrl) {
}
