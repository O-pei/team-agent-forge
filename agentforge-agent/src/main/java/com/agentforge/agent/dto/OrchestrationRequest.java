package com.agentforge.agent.dto;

public record OrchestrationRequest(String name, String description, String type, String configJson, Boolean enabled) {
}
