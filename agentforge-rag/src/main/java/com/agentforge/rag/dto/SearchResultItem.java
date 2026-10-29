package com.agentforge.rag.dto;

/**
 * 检索命中结果（对外响应，字段与契约 §4 knowledge search 对齐）。
 *
 * @param content    命中的分块文本
 * @param score      相似度（0~1，越大越相关）
 * @param documentId 来源文档 id
 * @param chunkIndex 文档内分块序号
 * @param filename   来源文档文件名
 */
public record SearchResultItem(
        String content,
        double score,
        Long documentId,
        int chunkIndex,
        String filename
) {
}
