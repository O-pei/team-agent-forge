package com.agentforge.audit.quota.model;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户额度实体（ai_user_quota 表）：tier_id 为空时继承默认套餐；
 * 两个 override 为 null 表示继承套餐额度，0 表示不限；granted_tokens 为管理员手动分配累计值。
 */
@Getter
@Setter
@Entity
@Table(name = "ai_user_quota")
public class UserQuotaEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private long tenantId = 1L;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "daily_limit_override")
    private Long dailyLimitOverride;

    @Column(name = "total_limit_override")
    private Long totalLimitOverride;

    @Column(name = "granted_tokens", nullable = false)
    private long grantedTokens;
}
