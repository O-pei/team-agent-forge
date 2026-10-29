package com.agentforge.audit.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 模型配置创建/更新请求（契约 2.2）。{@code apiKey} 为空表示更新时不修改。
 */
public record ModelConfigRequest(
        @NotBlank(message = "名称不能为空") String name,
        @NotBlank(message = "provider 不能为空") String provider,
        @NotBlank(message = "modelName 不能为空") String modelName,
        String baseUrl,
        String apiKey,
        @NotNull(message = "enabled 不能为空") Boolean enabled,
        @NotNull(message = "defaultModel 不能为空") Boolean defaultModel,
        Double temperature,
        Integer maxTokens) {
}
