package com.agentforge.agent.entity;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 多智能体编排定义（SEQUENTIAL / PARALLEL / ROUTING / SUPERVISOR）。
 * v0.1 既可用 AgentDefinition 的 type 加 subAgentIds 表达，也可在此保存可复用编排模板。
 */
@Entity
@Table(name = "agent_orchestration")
@Getter
@Setter
@NoArgsConstructor
public class AgentOrchestrationEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    /** JSON：{agentIds:[...], mergeStrategy:"..."} */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;
}
