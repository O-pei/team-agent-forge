package com.agentforge.rag.spi;

import java.util.List;

/**
 * 知识检索 SPI：agent 的 knowledge_search 工具只依赖本接口。
 */
public interface KnowledgeRetriever {

    List<RetrievedChunk> retrieve(Long knowledgeBaseId, String query, int topK, double threshold);

    List<RetrievedChunk> retrieveAll(List<Long> knowledgeBaseIds, String query, int topK, double threshold);
}
