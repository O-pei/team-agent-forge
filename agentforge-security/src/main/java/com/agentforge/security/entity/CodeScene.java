package com.agentforge.security.entity;

import com.agentforge.common.exception.BizException;

/**
 * 邮箱验证码场景，与契约 {@code REGISTER|LOGIN|RESET_PASSWORD} 一一对应。
 */
public enum CodeScene {
    REGISTER,
    LOGIN,
    RESET_PASSWORD;

    public static CodeScene from(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.badRequest("scene 不能为空");
        }
        try {
            return CodeScene.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("不支持的 scene: " + value);
        }
    }
}
