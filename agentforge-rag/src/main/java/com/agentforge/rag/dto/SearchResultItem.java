package com.agentforge.rag.dto;

/** 检索命中结果：分块文本、相似度（0~1，越大越相关）、来源文档 id 与文件名、文档内分块序号。 */
public record SearchResultItem(
        String content,
        double score,
        Long documentId,
        int chunkIndex,
        String filename
) {
}
