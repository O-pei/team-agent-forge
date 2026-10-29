package com.agentforge.rag.service;

import com.agentforge.common.api.PageResult;
import com.agentforge.common.exception.BizException;
import com.agentforge.rag.dto.KnowledgeBaseRequest;
import com.agentforge.rag.dto.KnowledgeBaseResponse;
import com.agentforge.rag.entity.KbKnowledgeBase;
import com.agentforge.rag.repository.ChunkRepository;
import com.agentforge.rag.repository.DocumentRepository;
import com.agentforge.rag.repository.KnowledgeBaseRepository;
import com.agentforge.rag.store.VectorStoreManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库 CRUD。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    public static final int DEFAULT_EMBEDDING_DIMENSION = 1024;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final VectorStoreManager vectorStoreManager;

    @Transactional(readOnly = true)
    public PageResult<KnowledgeBaseResponse> list(int page, int size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedSize, Sort.by(Sort.Direction.DESC, "id"));
        Page<KbKnowledgeBase> result = knowledgeBaseRepository.findAll(pageable);
        List<KnowledgeBaseResponse> records = result.getContent().stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
        return PageResult.of(records, result.getTotalElements(), normalizedPage, normalizedSize);
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseResponse get(long id) {
        return KnowledgeBaseResponse.from(require(id));
    }

    @Transactional
    public KnowledgeBaseResponse create(KnowledgeBaseRequest request) {
        KbKnowledgeBase kb = new KbKnowledgeBase();
        applyRequest(kb, request);
        kb.setEmbeddingDimension(DEFAULT_EMBEDDING_DIMENSION);
        kb.setDocumentCount(0);
        kb.setChunkCount(0);
        KbKnowledgeBase saved = knowledgeBaseRepository.save(kb);
        log.info("知识库已创建 id={} name={}", saved.getId(), saved.getName());
        return KnowledgeBaseResponse.from(saved);
    }

    @Transactional
    public KnowledgeBaseResponse update(long id, KnowledgeBaseRequest request) {
        KbKnowledgeBase kb = require(id);
        applyRequest(kb, request);
        KbKnowledgeBase saved = knowledgeBaseRepository.save(kb);
        return KnowledgeBaseResponse.from(saved);
    }

    @Transactional
    public void delete(long id) {
        KbKnowledgeBase kb = require(id);
        int chunks = chunkRepository.deleteByKbId(id);
        documentRepository.deleteAll(documentRepository.findByKbId(id));
        vectorStoreManager.remove(id);
        knowledgeBaseRepository.delete(kb);
        log.info("知识库已删除 id={} name={} chunks={}", id, kb.getName(), chunks);
    }

    /** 重新统计知识库下文档数 / 分块数（文档处理完成或删除后调用）。 */
    @Transactional
    public void refreshCounts(long kbId) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            long documents = documentRepository.countByKbId(kbId);
            long chunks = chunkRepository.countByKbId(kbId);
            kb.setDocumentCount((int) documents);
            kb.setChunkCount((int) chunks);
            knowledgeBaseRepository.save(kb);
            log.debug("知识库计数已刷新 kbId={} documents={} chunks={}", kbId, documents, chunks);
        });
    }

    public KbKnowledgeBase require(long id) {
        return knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("知识库不存在: " + id));
    }

    private void applyRequest(KbKnowledgeBase kb, KnowledgeBaseRequest request) {
        kb.setName(request.name().strip());
        kb.setDescription(request.description() == null ? null : request.description().strip());
        kb.setEmbeddingModelId(request.embeddingModelId());
    }

    static int normalizePage(int page) {
        return page < 1 ? 1 : page;
    }

    static int normalizeSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
