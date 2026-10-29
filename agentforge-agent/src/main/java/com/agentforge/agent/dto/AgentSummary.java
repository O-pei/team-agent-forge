package com.agentforge.agent.dto;

import java.time.LocalDateTime;

public record AgentSummary(
        Long id,
        String name,
        String description,
        String type,
        Boolean enabled,
        Integer currentVersion,
        Integer publishedVersion,
        LocalDateTime updatedAt) {
}
