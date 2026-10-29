package com.agentforge.datasource.entity;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 数据源（ds_datasource）。密码字段只存 AES-GCM 密文，绝不回显。
 */
@Getter
@Setter
@Entity
@Table(name = "ds_datasource")
public class DataSourceEntity extends BaseEntity {

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    /** MYSQL / POSTGRESQL / H2（对应 DataSourceType 枚举）。 */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, length = 1024)
    private String jdbcUrl;

    @Column(nullable = false, length = 128)
    private String username;

    /** AES-GCM 密文（v1: 前缀）。 */
    @Column(name = "password_encrypted", nullable = false, length = 1024)
    private String passwordEncrypted;

    @Column(nullable = false)
    private Boolean readOnly = Boolean.TRUE;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(nullable = false)
    private Integer maxPoolSize = 5;

    @PrePersist
    protected void fillDefaults() {
        if (tenantId == null) {
            tenantId = 1L;
        }
        if (readOnly == null) {
            readOnly = Boolean.TRUE;
        }
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (maxPoolSize == null) {
            maxPoolSize = 5;
        }
    }
}
