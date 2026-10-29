package com.agentforge.security.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 角色-权限关联（映射 V1 迁移的 {@code af_role_permission}，无审计字段）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "af_role_permission")
public class RolePermissionEntity {

    @EmbeddedId
    private RolePermissionId id;

    public RolePermissionEntity(Long roleId, Long permissionId) {
        this.id = new RolePermissionId(roleId, permissionId);
    }
}
