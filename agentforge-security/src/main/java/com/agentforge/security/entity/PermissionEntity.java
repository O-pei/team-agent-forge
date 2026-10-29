package com.agentforge.security.entity;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 权限（映射 V1 迁移的 {@code af_permission}），code 形如 {@code user:read}。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "af_permission")
public class PermissionEntity extends BaseEntity {

    @Column(nullable = false, length = 128)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 64)
    private String resource;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(length = 255)
    private String description;
}
