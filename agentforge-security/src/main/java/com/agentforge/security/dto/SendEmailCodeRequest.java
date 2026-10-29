package com.agentforge.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 发送邮箱验证码请求（契约 §2.1 POST /api/v1/auth/email-code）。
 *
 * @param email 目标邮箱
 * @param scene 场景：REGISTER / LOGIN / RESET_PASSWORD
 */
public record SendEmailCodeRequest(
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,

        @NotBlank(message = "场景不能为空")
        @Pattern(regexp = "REGISTER|LOGIN|RESET_PASSWORD", message = "scene 仅支持 REGISTER/LOGIN/RESET_PASSWORD")
        String scene) {
}
