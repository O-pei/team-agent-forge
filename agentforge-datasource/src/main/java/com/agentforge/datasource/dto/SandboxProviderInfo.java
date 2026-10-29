package com.agentforge.datasource.dto;

import java.util.List;

/**
 * 沙箱实现能力（GET /api/v1/sandbox/providers）。
 *
 * @param name        实现名：process / docker
 * @param available   当前环境是否可用
 * @param languages   支持的语言
 * @param description 说明（限制条件）
 */
public record SandboxProviderInfo(String name, boolean available, List<String> languages, String description) {
}
