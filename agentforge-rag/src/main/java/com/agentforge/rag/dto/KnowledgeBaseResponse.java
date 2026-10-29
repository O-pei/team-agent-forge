package com.agentforge.rag.dto;

import com.agentforge.rag.entity.KbKnowledgeBase;

import java.time.LocalDateTime;

/**
 * 知识库响应（不含任何密钥/内部路径）。
 */
public record KnowledgeBaseResponse(
        Long id,
        String name,
        String description,
        Long embeddingModelId,
        Integer embeddingDimension,
        Integer documentCount,
        Integer chunkCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static KnowledgeBaseResponse from(KbKnowledgeBase kb) {
        return new KnowledgeBaseResponse(
                kb.getId(),
                kb.getName(),
                kb.getDescription(),
                kb.getEmbeddingModelId(),
                kb.getEmbeddingDimension(),
                kb.getDocumentCount(),
                kb.getChunkCount(),
                kb.getCreatedAt(),
                kb.getUpdatedAt());
    }
}
