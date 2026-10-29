package com.agentforge.memory.controller;

import com.agentforge.common.api.ApiResponse;
import com.agentforge.common.api.PageResult;
import com.agentforge.memory.api.MemoryFactView;
import com.agentforge.memory.api.MemoryMessageView;
import com.agentforge.memory.api.MemorySessionSummaryView;
import com.agentforge.memory.service.MemoryAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆引擎管理接口：会话消息、会话摘要、长期记忆。
 */
@Tag(name = "记忆引擎")
@Validated
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryAdminService memoryAdminService;

    @Operation(summary = "查询会话消息（分页）")
    @GetMapping("/sessions/{sessionId}/messages")
    @PreAuthorize("hasAuthority('memory:read')")
    public ApiResponse<PageResult<MemoryMessageView>> listMessages(
            @PathVariable @Size(max = 64) String sessionId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(memoryAdminService.listMessages(sessionId, page, size));
    }

    @Operation(summary = "查询会话摘要")
    @GetMapping("/sessions/{sessionId}/summary")
    @PreAuthorize("hasAuthority('memory:read')")
    public ApiResponse<MemorySessionSummaryView> summarize(
            @PathVariable @Size(max = 64) String sessionId) {
        return ApiResponse.ok(memoryAdminService.summarize(sessionId));
    }

    @Operation(summary = "清空会话记忆")
    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAuthority('memory:write')")
    public ApiResponse<Void> clearSession(@PathVariable @Size(max = 64) String sessionId) {
        memoryAdminService.clearSession(sessionId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "查询长期记忆（分页，可按关键词召回）")
    @GetMapping("/facts")
    @PreAuthorize("hasAuthority('memory:read')")
    public ApiResponse<PageResult<MemoryFactView>> listFacts(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @Size(max = 200) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(memoryAdminService.listFacts(userId, keyword, page, size));
    }

    @Operation(summary = "删除长期记忆")
    @DeleteMapping("/facts/{factId}")
    @PreAuthorize("hasAuthority('memory:write')")
    public ApiResponse<Void> deleteFact(@PathVariable Long factId) {
        memoryAdminService.deleteFact(factId);
        return ApiResponse.ok(null);
    }
}
