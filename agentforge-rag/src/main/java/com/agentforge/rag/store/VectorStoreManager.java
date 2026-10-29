package com.agentforge.rag.store;

import com.agentforge.common.api.ErrorCode;
import com.agentforge.common.exception.BizException;
import com.agentforge.rag.entity.KbChunk;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 基于 {@link SimpleVectorStore} 的按知识库向量库管理：每个知识库一个 JSON 文件
 * {@code .data/vector-store/<kbId>.json}，启动时加载、写入后原子落盘。
 */
@Slf4j
@Component
public class VectorStoreManager {

    private final EmbeddingModel embeddingModel;
    private final Path baseDir;
    private final Map<Long, SimpleVectorStore> stores = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public VectorStoreManager(@Qualifier("hashingEmbeddingModel") EmbeddingModel embeddingModel,
                              @Value("${agentforge.rag.vector-store-dir:./.data/vector-store}") String vectorStoreDir) {
        this.embeddingModel = embeddingModel;
        this.baseDir = Paths.get(vectorStoreDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建向量库目录: " + baseDir, e);
        }
    }

    /** 启动时加载已有知识库向量（失败不影响启动，仅告警并在下次写入时重建）。 */
    @PostConstruct
    public void loadExisting() {
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        Long kbId = parseKbId(name);
                        if (kbId == null) {
                            return;
                        }
                        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
                        try {
                            store.load(path.toFile());
                            stores.put(kbId, store);
                            log.info("已加载向量库 kbId={} file={}", kbId, path.getFileName());
                        } catch (Exception e) {
                            log.warn("向量库加载失败 kbId={} error={}", kbId, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("扫描向量库目录失败 dir={} error={}", baseDir, e.getMessage());
        }
    }

    public Path fileOf(long kbId) {
        return baseDir.resolve(kbId + ".json");
    }

    /** 获取（必要时加载）指定知识库的向量库。 */
    public SimpleVectorStore store(long kbId) {
        return stores.computeIfAbsent(kbId, id -> {
            SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
            Path file = fileOf(id);
            if (Files.exists(file)) {
                try {
                    store.load(file.toFile());
                } catch (Exception e) {
                    log.warn("向量库加载失败，使用空库 kbId={} error={}", id, e.getMessage());
                }
            }
            return store;
        });
    }

    /**
     * 写入文档分块向量并持久化。
     *
     * @param kbId     知识库 id
     * @param chunks   已保存的分块（含数据库 id）
     * @param filename 文档文件名（写入元数据，检索结果可直接展示）
     */
    public void addChunks(long kbId, List<KbChunk> chunks, String filename) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        synchronized (lock) {
            SimpleVectorStore store = store(kbId);
            List<Document> documents = chunks.stream()
                    .map(chunk -> new Document(
                            chunk.getId().toString(),
                            chunk.getContent(),
                            metadataOf(chunk, filename)))
                    .toList();
            store.add(documents);
            persist(kbId, store);
        }
    }

    /** 删除某文档的全部分块向量并持久化。 */
    public void deleteByDocument(long kbId, long documentId) {
        synchronized (lock) {
            SimpleVectorStore store = store(kbId);
            try {
                store.delete(new FilterExpressionBuilder().eq("documentId", documentId).build());
            } catch (Exception e) {
                log.warn("删除向量失败 kbId={} documentId={} error={}", kbId, documentId, e.getMessage());
            }
            persist(kbId, store);
        }
    }

    /** 检索。 */
    public List<Document> search(long kbId, String query, int topK, double threshold) {
        synchronized (lock) {
            return store(kbId).similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .build());
        }
    }

    /** 删除整个知识库的向量（含文件）。 */
    public void remove(long kbId) {
        synchronized (lock) {
            stores.remove(kbId);
            try {
                Files.deleteIfExists(fileOf(kbId));
            } catch (IOException e) {
                log.warn("删除向量文件失败 kbId={} error={}", kbId, e.getMessage());
            }
        }
    }

    private void persist(long kbId, SimpleVectorStore store) {
        Path target = fileOf(kbId);
        Path tmp = baseDir.resolve(kbId + ".json.tmp");
        try {
            Files.createDirectories(baseDir);
            store.save(tmp.toFile());
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("向量库持久化失败 kbId={} error={}", kbId, e.getMessage());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "向量库持久化失败");
        }
    }

    private static Map<String, Object> metadataOf(KbChunk chunk, String filename) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kbId", chunk.getKbId());
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("filename", filename == null ? "" : filename);
        return metadata;
    }

    private static Long parseKbId(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            return Long.parseLong(filename.substring(0, dot));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
