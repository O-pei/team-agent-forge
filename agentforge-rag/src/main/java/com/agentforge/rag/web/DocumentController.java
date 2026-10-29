package com.agentforge.rag.web;

import com.agentforge.common.api.ApiResponse;
import com.agentforge.rag.dto.DocumentResponse;
import com.agentforge.rag.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档接口（契约 docs/04-API契约.md §4）。
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('rag:read')")
    public ApiResponse<DocumentResponse> get(@PathVariable long id) {
        return ApiResponse.ok(documentService.get(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('rag:write')")
    public ApiResponse<Void> delete(@PathVariable long id) {
        documentService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reindex")
    @PreAuthorize("hasAuthority('rag:write')")
    public ApiResponse<DocumentResponse> reindex(@PathVariable long id) {
        return ApiResponse.ok(documentService.reindex(id));
    }
}
