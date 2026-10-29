package com.agentforge.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 异步文档处理入口：@Async 线程中调用 {@link DocumentIngestionService}，
 * 失败时写 FAILED 状态，不阻塞上传请求。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final DocumentIngestionService documentIngestionService;

    @Async
    public void processAsync(long documentId) {
        try {
            documentIngestionService.ingest(documentId);
        } catch (Exception e) {
            log.error("文档异步处理失败 documentId={}", documentId, e);
            try {
                documentIngestionService.markFailed(documentId, e.getMessage());
            } catch (Exception fallback) {
                log.error("标记文档 FAILED 也失败 documentId={}", documentId, fallback);
            }
        }
    }
}
