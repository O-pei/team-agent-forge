package com.agentforge.agent.dto;

/**
 * 发布版本请求：version 为空时发布当前最新版本。
 */
public record PublishRequest(Integer version) {
}
