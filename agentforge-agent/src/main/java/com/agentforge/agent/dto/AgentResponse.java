package com.agentforge.agent.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AgentResponse(
        Long id,
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
        Boolean enabled,
        Integer currentVersion,
        Integer publishedVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
