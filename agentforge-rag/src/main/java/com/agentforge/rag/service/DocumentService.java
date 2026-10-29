package com.agentforge.rag.service;

import com.agentforge.common.api.PageResult;
import com.agentforge.common.exception.BizException;
import com.agentforge.rag.dto.DocumentResponse;
import com.agentforge.rag.entity.DocumentStatus;
import com.agentforge.rag.entity.KbDocument;
import com.agentforge.rag.parse.DocumentParser;
import com.agentforge.rag.repository.ChunkRepository;
import com.agentforge.rag.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 文档上传 / 查询 / 删除 / 重建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentParser documentParser;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentProcessingService documentProcessingService;
    private final com.agentforge.rag.store.VectorStoreManager vectorStoreManager;

    @Value("${agentforge.rag.upload-dir:./.data/rag-uploads}")
    private String uploadDir;

    /**
     * 上传文档：白名单校验后原文落盘，建 PENDING 记录再异步解析。
     * 本方法不加 @Transactional，保证异步线程启动前记录已提交。
     */
    public DocumentResponse upload(long kbId, MultipartFile file) {
        knowledgeBaseService.require(kbId);
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件不能为空");
        }
        String filename = documentParser.validate(file.getOriginalFilename(), file.getSize());
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw BizException.badRequest("读取上传文件失败: " + e.getMessage());
        }
        documentParser.validate(filename, content.length);

        Path stored = storeFile(kbId, filename, content);

        KbDocument document = new KbDocument();
        document.setKbId(kbId);
        document.setFilename(filename);
        document.setContentType(file.getContentType());
        document.setFileSize((long) content.length);
        document.setStatus(DocumentStatus.PENDING);
        document.setChunkCount(0);
        document.setStoragePath(stored.toString());
        KbDocument saved = documentRepository.save(document);

        knowledgeBaseService.refreshCounts(kbId);
        documentProcessingService.processAsync(saved.getId());
        log.info("文档已上传并排队解析 documentId={} kbId={} filename={} size={}",
                saved.getId(), kbId, filename, content.length);
        return DocumentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResult<DocumentResponse> list(long kbId, int page, int size, String status) {
        knowledgeBaseService.require(kbId);
        int normalizedPage = KnowledgeBaseService.normalizePage(page);
        int normalizedSize = KnowledgeBaseService.normalizeSize(size);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedSize,
                Sort.by(Sort.Direction.DESC, "id"));
        Page<KbDocument> result;
        if (status == null || status.isBlank()) {
            result = documentRepository.findByKbId(kbId, pageable);
        } else {
            DocumentStatus documentStatus;
            try {
                documentStatus = DocumentStatus.valueOf(status.strip().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw BizException.badRequest("非法文档状态: " + status);
            }
            result = documentRepository.findByKbIdAndStatus(kbId, documentStatus, pageable);
        }
        return PageResult.of(result.getContent().stream().map(DocumentResponse::from).toList(),
                result.getTotalElements(), normalizedPage, normalizedSize);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(long id) {
        return DocumentResponse.from(require(id));
    }

    @Transactional
    public void delete(long id) {
        KbDocument document = require(id);
        chunkRepository.deleteByDocumentId(id);
        vectorStoreManager.deleteByDocument(document.getKbId(), id);
        documentRepository.delete(document);
        deleteStoredFile(document);
        knowledgeBaseService.refreshCounts(document.getKbId());
        log.info("文档已删除 documentId={} kbId={}", id, document.getKbId());
    }

    /** 重建索引：清空旧分块与向量后重新异步解析。 */
    @Transactional
    public DocumentResponse reindex(long id) {
        KbDocument document = require(id);
        if (document.getStoragePath() == null || !Files.exists(Paths.get(document.getStoragePath()))) {
            throw BizException.badRequest("原始文件不存在，无法重建索引");
        }
        chunkRepository.deleteByDocumentId(id);
        vectorStoreManager.deleteByDocument(document.getKbId(), id);
        document.setStatus(DocumentStatus.PENDING);
        document.setChunkCount(0);
        document.setErrorMessage(null);
        KbDocument saved = documentRepository.save(document);
        knowledgeBaseService.refreshCounts(document.getKbId());
        documentProcessingService.processAsync(saved.getId());
        return DocumentResponse.from(saved);
    }

    public KbDocument require(long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("文档不存在: " + id));
    }

    private Path storeFile(long kbId, String filename, byte[] content) {
        try {
            Path dir = Paths.get(uploadDir, String.valueOf(kbId)).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String extension = "";
            int dot = filename.lastIndexOf('.');
            if (dot >= 0) {
                extension = filename.substring(dot).toLowerCase();
            }
            Path target = dir.resolve(UUID.randomUUID() + extension);
            Files.write(target, content);
            return target;
        } catch (IOException e) {
            log.error("保存上传文件失败 kbId={} filename={}", kbId, filename, e);
            throw new BizException(com.agentforge.common.api.ErrorCode.INTERNAL_ERROR, "保存上传文件失败");
        }
    }

    private void deleteStoredFile(KbDocument document) {
        if (document.getStoragePath() == null) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(document.getStoragePath()));
        } catch (IOException e) {
            log.warn("删除上传文件失败 path={} error={}", document.getStoragePath(), e.getMessage());
        }
    }
}
