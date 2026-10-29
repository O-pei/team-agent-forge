package com.agentforge.agent.entity;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 运行步骤：MODEL（模型调用）或 TOOL（工具调用）。
 */
@Entity
@Table(name = "agent_run_step")
@Getter
@Setter
@NoArgsConstructor
public class AgentRunStepEntity extends BaseEntity {

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Column(name = "agent_name", length = 64)
    private String agentName;

    @Column(name = "node_name", length = 64)
    private String nodeName;

    /** MODEL / TOOL */
    @Column(name = "step_type", nullable = false, length = 16)
    private String stepType;

    @Column(name = "tool_name", length = 64)
    private String toolName;

    @Column(name = "input", columnDefinition = "TEXT")
    private String input;

    @Column(name = "output", columnDefinition = "TEXT")
    private String output;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "elapsed_ms", nullable = false)
    private Long elapsedMs;
}
