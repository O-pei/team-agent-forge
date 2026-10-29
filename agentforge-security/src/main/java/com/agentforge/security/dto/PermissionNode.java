package com.agentforge.security.dto;

import java.util.List;

/**
 * 权限树节点（契约 §2.3 GET /api/v1/permissions）：根节点按资源分组，叶子为具体权限码。
 */
public record PermissionNode(
        String code,
        String name,
        String resource,
        String action,
        String description,
        List<PermissionNode> children) {
}
