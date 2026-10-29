package com.agentforge.security.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 更新角色请求（契约 §2.3 PUT /api/v1/roles/{id}）：名称 / 描述 / 权限集合。
 */
public record UpdateRoleRequest(
        @Size(max = 64, message = "角色名称最长 64 个字符")
        String name,

        @Size(max = 255, message = "描述最长 255 个字符")
        String description,

        List<Long> permissionIds) {
}
