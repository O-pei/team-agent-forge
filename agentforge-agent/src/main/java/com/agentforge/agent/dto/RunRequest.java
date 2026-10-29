package com.agentforge.agent.dto;

/**
 * 非流式运行请求，对应契约 POST /api/v1/agents/{id}/run。
 */
public record RunRequest(String message, String sessionId) {
}
