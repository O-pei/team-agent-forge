package com.agentforge.security.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 用户-角色关联（映射 V1 迁移的 {@code af_user_role}，无审计字段）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "af_user_role")
public class UserRoleEntity {

    @EmbeddedId
    private UserRoleId id;

    public UserRoleEntity(Long userId, Long roleId) {
        this.id = new UserRoleId(userId, roleId);
    }
}
