package com.agentforge.audit.api;

import java.time.LocalDateTime;

/**
 * 模型配置响应（密钥只返回掩码，不回显明文）。
 */
public record ModelConfigResponse(
        Long id,
        String name,
        String provider,
        String modelName,
        String baseUrl,
        Boolean enabled,
        Boolean defaultModel,
        Double temperature,
        Integer maxTokens,
        boolean hasApiKey,
        String apiKeyMasked,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
