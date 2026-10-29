package com.agentforge.audit.quota.api;

import java.time.LocalDateTime;

/**
 * 用户额度视图：额度 0 或 remaining 为 -1 表示不限，unlimited 为 true 时两项剩余量均为 -1。
 */
public record UserQuotaView(
        Long userId,
        String username,
        String email,
        String nickname,
        String status,
        Long tierId,
        String tierCode,
        String tierName,
        long dailyLimit,
        long dailyUsed,
        long dailyRemaining,
        long totalLimit,
        long totalUsed,
        long totalRemaining,
        long grantedTokens,
        boolean unlimited,
        LocalDateTime resetAt) {
}
