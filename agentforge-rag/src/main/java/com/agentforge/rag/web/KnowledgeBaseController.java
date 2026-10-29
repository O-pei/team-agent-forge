package com.agentforge.rag.web;

import com.agentforge.common.api.ApiResponse;
import com.agentforge.common.api.PageResult;
import com.agentforge.rag.dto.DocumentResponse;
import com.agentforge.rag.dto.KnowledgeBaseRequest;
import com.agentforge.rag.dto.KnowledgeBaseResponse;
import com.agentforge.rag.dto.KnowledgeSearchRequest;
import com.agentforge.rag.dto.SearchResultItem;
import com.agentforge.rag.service.DocumentService;
import com.agentforge.rag.service.KnowledgeBaseService;
import com.agentforge.rag.service.KnowledgeRetrievalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库与文档接口（契约 docs/04-API契约.md §4）。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    @GetMapping
    @PreAuthorize("hasAuthority('rag:read')")
    public ApiResponse<PageResult<KnowledgeBaseResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(knowledgeBaseService.list(page, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('rag:write')")
    public ApiResponse<KnowledgeBaseResponse> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeBaseService.create(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('rag:read')")
    public ApiResponse<KnowledgeBaseResponse> get(@PathVariable long id) {
        return ApiResponse.ok(knowledgeBaseService.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('rag:write')")
    public ApiResponse<KnowledgeBaseResponse> update(@PathVariable long id,
                                                     @Valid @RequestBody KnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeBaseService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('rag:write')")
    public ApiResponse<Void> delete(@PathVariable long id) {
        knowledgeBaseService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/search")
    @PreAuthorize("hasAuthority('rag:read')")
    public ApiResponse<List<SearchResultItem>> search(@PathVariable long id,
                                                      @Valid @RequestBody KnowledgeSearchRequest request) {
        return ApiResponse.ok(knowledgeRetrievalService.search(
                id, request.query(), request.topKOrDefault(), request.thresholdOrDefault()));
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize("hasAuthority('rag:read')")
    public ApiResponse<PageResult<DocumentResponse>> listDocuments(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(documentService.list(id, page, size, status));
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasAuthority('rag:write')")
    public ApiResponse<DocumentResponse> upload(@PathVariable long id,
                                                @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(documentService.upload(id, file));
    }
}
