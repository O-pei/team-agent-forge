package com.agentforge.rag.service;

import com.agentforge.rag.dto.SearchResultItem;
import com.agentforge.rag.spi.KnowledgeRetriever;
import com.agentforge.rag.spi.RetrievedChunk;
import com.agentforge.rag.store.VectorStoreManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 语义检索：实现冻结的 {@link KnowledgeRetriever} SPI，并按契约输出 {@link SearchResultItem}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService implements KnowledgeRetriever {

    public static final int MAX_TOP_K = 20;
    public static final double DEFAULT_THRESHOLD = 0.3;

    private final VectorStoreManager vectorStoreManager;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 知识库检索（HTTP 接口使用）。
     */
    public List<SearchResultItem> search(long kbId, String query, int topK, double threshold) {
        knowledgeBaseService.require(kbId);
        return retrieve(kbId, query, topK, threshold).stream()
                .map(hit -> new SearchResultItem(
                        hit.content(),
                        hit.score(),
                        hit.documentId(),
                        hit.chunkIndex(),
                        hit.filename()))
                .toList();
    }

    @Override
    public List<RetrievedChunk> retrieve(Long knowledgeBaseId, String query, int topK, double threshold) {
        if (knowledgeBaseId == null || query == null || query.isBlank()) {
            return List.of();
        }
        int k = topK <= 0 ? 5 : Math.min(topK, MAX_TOP_K);
        double minScore = clampThreshold(threshold);
        List<Document> documents = vectorStoreManager.search(knowledgeBaseId, query, k, minScore);
        List<RetrievedChunk> hits = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            hits.add(new RetrievedChunk(
                    doc.getText(),
                    doc.getScore() == null ? 0.0 : doc.getScore(),
                    toLong(metadata.get("documentId")),
                    toString(metadata.get("filename")),
                    toInt(metadata.get("chunkIndex")),
                    metadata));
        }
        log.debug("检索完成 kbId={} query={} topK={} hits={}", knowledgeBaseId, query, k, hits.size());
        return hits;
    }

    @Override
    public List<RetrievedChunk> retrieveAll(List<Long> knowledgeBaseIds, String query, int topK, double threshold) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        int k = topK <= 0 ? 5 : Math.min(topK, MAX_TOP_K);
        List<RetrievedChunk> all = new ArrayList<>();
        for (Long kbId : knowledgeBaseIds) {
            if (kbId != null) {
                all.addAll(retrieve(kbId, query, k, threshold));
            }
        }
        all.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return all.size() <= k ? all : List.copyOf(all.subList(0, k));
    }

    private static double clampThreshold(double threshold) {
        if (Double.isNaN(threshold)) {
            return DEFAULT_THRESHOLD;
        }
        return Math.max(0.0, Math.min(1.0, threshold));
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String toString(Object value) {
        return value == null ? "" : value.toString();
    }
}
