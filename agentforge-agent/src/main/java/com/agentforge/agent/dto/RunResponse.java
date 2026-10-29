package com.agentforge.agent.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RunResponse(
        Long id,
        Long agentId,
        Integer agentVersion,
        String sessionId,
        String status,
        String input,
        String output,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        String modelName,
        String errorMessage,
        String traceId,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long elapsedMs,
        List<RunStepResponse> steps) {
}
