package com.agentforge.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 知识库创建/更新请求。
 */
public record KnowledgeBaseRequest(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 128, message = "知识库名称最长 128 字符")
        String name,

        @Size(max = 512, message = "描述最长 512 字符")
        String description,

        Long embeddingModelId
) {
}
