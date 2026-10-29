package com.agentforge.security.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 管理员更新用户请求（契约 §2.2 PUT /api/v1/users/{id}）：昵称 / 状态 / 角色。
 */
public record UpdateUserRequest(
        @Size(max = 64, message = "昵称最长 64 个字符")
        String nickname,

        @Pattern(regexp = "ACTIVE|DISABLED", message = "status 仅支持 ACTIVE/DISABLED")
        String status,

        List<Long> roleIds) {
}
