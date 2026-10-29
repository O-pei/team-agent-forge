package com.agentforge.audit.quota.model;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 额度套餐实体（ai_quota_tier 表）：每日与总额度，0 表示不限；builtin 套餐不可删除。
 */
@Getter
@Setter
@Entity
@Table(name = "ai_quota_tier")
public class QuotaTierEntity extends BaseEntity {

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "daily_token_limit", nullable = false)
    private long dailyTokenLimit;

    @Column(name = "total_token_limit", nullable = false)
    private long totalTokenLimit;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean builtin;
}
