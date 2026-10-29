package com.agentforge.rag.spi;

import java.util.Map;

/**
 * 一次检索命中（跨模块 DTO）。
 */
public record RetrievedChunk(
        String content,
        double score,
        Long documentId,
        String filename,
        int chunkIndex,
        Map<String, Object> metadata) {
}
