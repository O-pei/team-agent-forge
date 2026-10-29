package com.agentforge.agent.controller;

import com.agentforge.agent.dto.AgentCreateRequest;
import com.agentforge.agent.dto.AgentResponse;
import com.agentforge.agent.dto.AgentSummary;
import com.agentforge.agent.dto.AgentUpdateRequest;
import com.agentforge.agent.dto.AgentVersionResponse;
import com.agentforge.agent.dto.PublishRequest;
import com.agentforge.agent.service.AgentService;
import com.agentforge.common.api.ApiResponse;
import com.agentforge.common.api.PageResult;
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

import java.util.List;

/**
 * Agent 定义 CRUD / 发布 / 版本（契约 §2.6）。
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @GetMapping
    @PreAuthorize("hasAuthority('agent:read')")
    public ApiResponse<PageResult<AgentSummary>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(agentService.page(page, size, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:read')")
    public ApiResponse<AgentResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(agentService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:write')")
    public ApiResponse<AgentResponse> create(@RequestBody AgentCreateRequest request) {
        return ApiResponse.ok(agentService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:write')")
    public ApiResponse<AgentResponse> update(@PathVariable Long id,
                                             @RequestBody AgentUpdateRequest request) {
        return ApiResponse.ok(agentService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:write')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        agentService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('agent:write')")
    public ApiResponse<AgentResponse> publish(@PathVariable Long id,
                                              @RequestBody(required = false) PublishRequest request) {
        return ApiResponse.ok(agentService.publish(id, request == null ? null : request.version()));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('agent:read')")
    public ApiResponse<List<AgentVersionResponse>> versions(@PathVariable Long id) {
        return ApiResponse.ok(agentService.versions(id));
    }
}
