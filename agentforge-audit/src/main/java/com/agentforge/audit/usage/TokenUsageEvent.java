package com.agentforge.audit.usage;

/**
 * 一次模型调用的 Token 用量事件（由 agent 层在调用完成后上报）。
 */
public record TokenUsageEvent(
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
        boolean stream,
        boolean success,
        long latencyMs,
        String traceId) {
}
