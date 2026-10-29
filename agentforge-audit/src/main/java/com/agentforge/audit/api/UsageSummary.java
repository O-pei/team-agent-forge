package com.agentforge.audit.api;

import java.math.BigDecimal;

/**
 * Token 用量汇总（契约 GET /api/v1/audit/usage/summary）。
 */
public record UsageSummary(
        long totalPromptTokens,
        long totalCompletionTokens,
        long totalTokens,
        BigDecimal totalCost,
        long requests) {
}
