package com.agentforge.audit.quota.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 手动分配流水实体（ai_quota_grant_log 表），日志表不维护 version 与 updated_at。
 */
@Getter
@Setter
@Entity
@Table(name = "ai_quota_grant_log")
public class QuotaGrantLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private long tenantId = 1L;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(nullable = false)
    private long amount;

    @Column(length = 255)
    private String reason;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
