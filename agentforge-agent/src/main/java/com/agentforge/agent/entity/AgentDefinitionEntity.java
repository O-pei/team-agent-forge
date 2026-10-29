package com.agentforge.agent.entity;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Agent 定义（当前最新配置）。历史配置在 agent_version 中不可变保存。
 */
@Entity
@Table(name = "agent_definition")
@Getter
@Setter
@NoArgsConstructor
public class AgentDefinitionEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    /** {@link com.agentforge.agent.domain.AgentType} */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "max_iterations", nullable = false)
    private Integer maxIterations;

    /** JSON 数组：["knowledge_search","calculator"] */
    @Column(name = "tools_json", columnDefinition = "TEXT")
    private String toolsJson;

    /** JSON 数组：[2,3]（SEQUENTIAL/PARALLEL/ROUTING/SUPERVISOR 的子 Agent） */
    @Column(name = "sub_agent_ids_json", columnDefinition = "TEXT")
    private String subAgentIdsJson;

    /** 可选结构化输出类型的全限定类名 */
    @Column(name = "output_type", length = 255)
    private String outputType;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "current_version", nullable = false)
    private Integer currentVersion;

    @Column(name = "published_version")
    private Integer publishedVersion;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted;
}
