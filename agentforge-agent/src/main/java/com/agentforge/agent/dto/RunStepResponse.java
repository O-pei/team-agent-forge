package com.agentforge.agent.dto;

public record RunStepResponse(
        Integer seqNo,
        String agentName,
        String nodeName,
        String stepType,
        String toolName,
        String input,
        String output,
        String status,
        Long elapsedMs) {
}
