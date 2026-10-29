package com.agentforge.agent.controller;

import com.agentforge.agent.dto.OrchestrationRequest;
import com.agentforge.agent.dto.OrchestrationResponse;
import com.agentforge.agent.service.OrchestrationService;
import com.agentforge.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 编排配置 CRUD（契约 §2.6）。
 */
@RestController
@RequestMapping("/api/v1/orchestrations")
@RequiredArgsConstructor
public class OrchestrationController {

    private final OrchestrationService orchestrationService;

    @GetMapping
    @PreAuthorize("hasAuthority('agent:read')")
    public ApiResponse<List<OrchestrationResponse>> list() {
        return ApiResponse.ok(orchestrationService.list());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:read')")
    public ApiResponse<OrchestrationResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(orchestrationService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:write')")
    public ApiResponse<OrchestrationResponse> create(@RequestBody OrchestrationRequest request) {
        return ApiResponse.ok(orchestrationService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:write')")
    public ApiResponse<OrchestrationResponse> update(@PathVariable Long id,
                                                     @RequestBody OrchestrationRequest request) {
        return ApiResponse.ok(orchestrationService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:write')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        orchestrationService.delete(id);
        return ApiResponse.ok(null);
    }
}
