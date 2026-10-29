package com.agentforge.agent.dto;

import java.util.List;

/**
 * SSE 对话请求，对应契约 POST /api/v1/chat/stream。
 */
public record ChatStreamRequest(Long agentId, String sessionId, String message, List<String> attachments) {
}
