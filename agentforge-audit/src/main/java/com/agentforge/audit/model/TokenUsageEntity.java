package com.agentforge.audit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Token 用量明细实体（ai_token_usage 表），日志表不维护 version 和 updated_at。
 */
@Getter
@Setter
@Entity
@Table(name = "ai_token_usage")
public class TokenUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "model_name", length = 128)
    private String modelName;

    @Column(length = 32)
    private String provider;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal cost;

    @Column(nullable = false)
    private boolean stream;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (cost == null) {
            cost = BigDecimal.ZERO;
        }
    }
}
