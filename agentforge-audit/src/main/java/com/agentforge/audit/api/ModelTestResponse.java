package com.agentforge.audit.api;

/**
 * 模型连通性测试响应：返回延迟、模型回显与结果消息。
 */
public record ModelTestResponse(
        boolean success,
        long latencyMs,
        String modelName,
        String reply,
        String message) {
}
