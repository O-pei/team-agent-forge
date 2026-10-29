package com.agentforge.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 知识库检索请求：query 必填，topK 默认 5（上限 20），threshold 默认 0.3。
 */
public record KnowledgeSearchRequest(
        @NotBlank(message = "查询内容不能为空")
        @Size(max = 2000, message = "查询内容最长 2000 字符")
        String query,

        @Min(value = 1, message = "topK 最小 1")
        @Max(value = 20, message = "topK 最大 20")
        Integer topK,

        Double threshold
) {

    public int topKOrDefault() {
        return topK == null ? 5 : topK;
    }

    public double thresholdOrDefault() {
        return threshold == null ? 0.3 : threshold;
    }
}
