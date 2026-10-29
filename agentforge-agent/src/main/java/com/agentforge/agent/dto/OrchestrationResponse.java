package com.agentforge.agent.dto;

import java.time.LocalDateTime;

public record OrchestrationResponse(
        Long id,
        String name,
        String description,
        String type,
        String configJson,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
