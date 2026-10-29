package com.agentforge.agent.entity;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 会话（一个 Agent 下的一段多轮对话）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "agent_session")
public class AgentSessionEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "message_count", nullable = false)
    private Integer messageCount = 0;
}
