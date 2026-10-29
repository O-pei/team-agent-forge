package com.agentforge.audit.quota.api;

import java.time.LocalDateTime;

/**
 * 手动分配流水视图。
 */
public record QuotaGrantView(
        Long id,
        Long operatorId,
        String operatorName,
        long amount,
        String reason,
        long balanceAfter,
        LocalDateTime createdAt) {
}
