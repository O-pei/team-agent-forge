package com.agentforge.agent.dto;

import java.util.List;

/**
 * 创建 Agent 草稿（生成 v1）。
 */
public record AgentCreateRequest(
        String name,
        String description,
        String type,
        String systemPrompt,
        Long modelId,
        Double temperature,
        Integer maxIterations,
        List<String> tools,
        List<Long> subAgentIds,
        String outputType,
        Boolean enabled) {
}
