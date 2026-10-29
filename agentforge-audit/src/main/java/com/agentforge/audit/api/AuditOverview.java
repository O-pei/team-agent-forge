package com.agentforge.audit.api;

import java.math.BigDecimal;

/**
 * 工作台审计概览卡片。
 */
public record AuditOverview(
        long totalModels,
        long enabledModels,
        long todayRequests,
        long todayTokens,
        BigDecimal todayCost,
        long totalRequests,
        long totalTokens,
        BigDecimal totalCost) {
}
