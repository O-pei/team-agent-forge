package com.agentforge.audit.quota.api;

import java.time.LocalDateTime;

/**
 * 套餐视图：额度 0 表示不限，userCount 为当前使用该套餐的用户数。
 */
public record QuotaTierView(
        Long id,
        String code,
        String name,
        String description,
        long dailyTokenLimit,
        long totalTokenLimit,
        int sortOrder,
        boolean enabled,
        boolean builtin,
        long userCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
