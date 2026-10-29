package com.agentforge.audit.api;

/**
 * 模型连通性测试响应：发送 {@code ping}，返回延迟与模型回显。
 */
public record ModelTestResponse(
        boolean success,
        long latencyMs,
        String modelName,
        String reply,
        String message) {
}
