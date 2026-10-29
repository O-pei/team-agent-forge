package com.agentforge.audit.gateway;

/**
 * 模型配置的对外只读视图（跨模块 DTO）。
 */
public record ChatModelInfo(Long id, String name, String provider, String modelName, boolean isDefault, boolean enabled) {
}
