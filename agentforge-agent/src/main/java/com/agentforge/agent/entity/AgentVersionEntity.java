package com.agentforge.agent.entity;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Agent 配置版本快照（不可变历史）；业务版本号用 version_no，避免与乐观锁 version 冲突。
 */
@Entity
@Table(name = "agent_version")
@Getter
@Setter
@NoArgsConstructor
public class AgentVersionEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "config_json", columnDefinition = "TEXT", nullable = false)
    private String configJson;

    @Column(name = "published", nullable = false)
    private Boolean published;
}
