package com.agentforge.rag.dto;

import com.agentforge.rag.entity.DocumentStatus;
import com.agentforge.rag.entity.KbDocument;

import java.time.LocalDateTime;

/**
 * 文档响应。
 */
public record DocumentResponse(
        Long id,
        Long knowledgeBaseId,
        String filename,
        String contentType,
        Long fileSize,
        DocumentStatus status,
        Integer chunkCount,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static DocumentResponse from(KbDocument doc) {
        return new DocumentResponse(
                doc.getId(),
                doc.getKbId(),
                doc.getFilename(),
                doc.getContentType(),
                doc.getFileSize(),
                doc.getStatus(),
                doc.getChunkCount(),
                doc.getErrorMessage(),
                doc.getCreatedAt(),
                doc.getUpdatedAt());
    }
}
