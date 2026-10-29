package com.agentforge.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 管理员重置用户密码请求（契约 §2.2 PUT /api/v1/users/{id}/password）。
 */
public record AdminResetPasswordRequest(
        @NotBlank(message = "密码不能为空")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$", message = "密码至少 8 位且必须包含字母和数字")
        String password) {
}
