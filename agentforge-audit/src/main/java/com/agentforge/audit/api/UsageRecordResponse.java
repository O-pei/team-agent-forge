package com.agentforge.audit.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Token 用量明细记录（管理端分页查询）。
 */
public record UsageRecordResponse(
        Long id,
        String traceId,
        Long tenantId,
        Long userId,
        Long agentId,
        Long runId,
        String sessionId,
        Long modelId,
        String modelName,
        String provider,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        BigDecimal cost,
        boolean stream,
        boolean success,
        long latencyMs,
        LocalDateTime createdAt) {
}
