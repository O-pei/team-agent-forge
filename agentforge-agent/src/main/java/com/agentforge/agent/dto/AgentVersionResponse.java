package com.agentforge.agent.dto;

import java.time.LocalDateTime;

public record AgentVersionResponse(
        Long id,
        Long agentId,
        Integer version,
        Boolean published,
        String configJson,
        LocalDateTime createdAt) {
}
