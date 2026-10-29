package com.agentforge.datasource.dto;

import java.util.List;

/** 沙箱实现能力：实现名（process/docker）、当前环境是否可用、支持语言与限制说明。 */
public record SandboxProviderInfo(String name, boolean available, List<String> languages, String description) {
}
