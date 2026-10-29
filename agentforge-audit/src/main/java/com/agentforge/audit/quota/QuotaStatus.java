package com.agentforge.audit.quota;

import java.time.LocalDateTime;

/**
 * 用户额度快照。limit 或 remaining 为 0 表示额度已用尽；dailyLimit/totalLimit 为 0 表示不限。
 * unlimited 为 true 时 remaining 固定为 -1。resetAt 为下一个自然日额度的重置时间。
 */
public record QuotaStatus(
        Long userId,
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
