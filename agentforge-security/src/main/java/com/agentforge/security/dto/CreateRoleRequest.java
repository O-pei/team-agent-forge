package com.agentforge.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建角色请求（契约 §2.3 POST /api/v1/roles）。
 */
public record CreateRoleRequest(
        @NotBlank(message = "角色编码不能为空")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "角色编码为大写字母/数字/下划线，2~32 位")
        String code,

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64, message = "角色名称最长 64 个字符")
        String name,

        @Size(max = 255, message = "描述最长 255 个字符")
        String description,

        List<Long> permissionIds) {
}
