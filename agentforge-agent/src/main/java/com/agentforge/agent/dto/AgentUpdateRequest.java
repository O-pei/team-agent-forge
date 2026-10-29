package com.agentforge.agent.dto;

import java.util.List;

/**
 * 更新 Agent：所有字段可选，非 null 才覆盖；更新后生成新版本（历史不可变）。
 */
public record AgentUpdateRequest(
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
