package com.agentforge.rag.service;

import com.agentforge.common.exception.BizException;
import com.agentforge.rag.chunk.TextChunker;
import com.agentforge.rag.entity.DocumentStatus;
import com.agentforge.rag.entity.KbChunk;
import com.agentforge.rag.entity.KbDocument;
import com.agentforge.rag.parse.DocumentParser;
import com.agentforge.rag.repository.ChunkRepository;
import com.agentforge.rag.repository.DocumentRepository;
import com.agentforge.rag.store.VectorStoreManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析、切分、向量化的真正执行者（同步事务逻辑），由 DocumentProcessingService 通过 @Async 调用。
 * 拆成两个 Bean 是为了避免 Spring 自调用导致 @Transactional 与 @Async 失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentParser documentParser;
    private final VectorStoreManager vectorStoreManager;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 解析文档 → 切分 → 落库 → 写向量库 → 置 READY。
     */
    @Transactional
    public void ingest(long documentId) {
        KbDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> BizException.notFound("文档不存在: " + documentId));
        document.setStatus(DocumentStatus.PROCESSING);
        document.setErrorMessage(null);
        documentRepository.save(document);

        byte[] content;
        try {
            Path path = Paths.get(document.getStoragePath());
            content = Files.readAllBytes(path);
        } catch (Exception e) {
            throw new BizException(com.agentforge.common.api.ErrorCode.INTERNAL_ERROR, "读取上传文件失败: " + e.getMessage());
        }
        String text = documentParser.parse(content, document.getFilename(), document.getContentType());
        List<String> parts = TextChunker.split(text, TextChunker.DEFAULT_CHUNK_SIZE, TextChunker.DEFAULT_OVERLAP);
        if (parts.isEmpty()) {
            throw new BizException(com.agentforge.common.api.ErrorCode.BAD_REQUEST, "文档切分结果为空");
        }

        chunkRepository.deleteByDocumentId(documentId);
        List<KbChunk> chunks = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            KbChunk chunk = new KbChunk();
            chunk.setKbId(document.getKbId());
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent(parts.get(i));
            chunk.setCharCount(parts.get(i).length());
            chunks.add(chunk);
        }
        List<KbChunk> saved = chunkRepository.saveAll(chunks);

        document.setChunkCount(saved.size());
        document.setStatus(DocumentStatus.READY);
        document.setErrorMessage(null);
        documentRepository.save(document);

        vectorStoreManager.addChunks(document.getKbId(), saved, document.getFilename());
        knowledgeBaseService.refreshCounts(document.getKbId());
        log.info("文档处理完成 documentId={} kbId={} chunks={}", documentId, document.getKbId(), saved.size());
    }

    /** 处理失败：清空分块并置 FAILED（独立事务，避免被回滚）。 */
    @Transactional
    public void markFailed(long documentId, String errorMessage) {
        documentRepository.findById(documentId).ifPresent(document -> {
            chunkRepository.deleteByDocumentId(documentId);
            document.setStatus(DocumentStatus.FAILED);
            document.setChunkCount(0);
            document.setErrorMessage(errorMessage == null ? "未知错误" : truncate(errorMessage));
            documentRepository.save(document);
            knowledgeBaseService.refreshCounts(document.getKbId());
            log.warn("文档处理失败 documentId={} error={}", documentId, errorMessage);
        });
    }

    private static String truncate(String message) {
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
