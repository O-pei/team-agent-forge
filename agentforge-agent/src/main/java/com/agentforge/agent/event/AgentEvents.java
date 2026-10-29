package com.agentforge.agent.event;

/**
 * SSE 事件负载（record 序列化为 JSON）。
 */
public final class AgentEvents {

    private AgentEvents() {
    }

    public record RunStarted(Long runId, Long agentId, String sessionId, String model) {
    }

    public record MessageDelta(String content) {
    }

    public record MessageCompleted(String content) {
    }

    public record ToolCall(String toolName, String arguments, String riskLevel) {
    }

    public record ToolResult(String toolName, String resultSummary, boolean success, long elapsedMs) {
    }

    public record Citation(int index, Long documentId, String filename, int chunkIndex, double score) {
    }

    public record Usage(String model, long promptTokens, long completionTokens, long totalTokens, double cost) {
    }

    public record Interrupted(String approvalId, String toolName, String arguments, String reason) {
    }

    public record RunCompleted(Long runId, String status, long elapsedMs, Usage usage) {
    }

    public record ErrorData(int code, String message) {
    }
}
