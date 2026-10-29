package com.agentforge.agent.controller;

import com.agentforge.agent.dto.ApprovalRequest;
import com.agentforge.agent.dto.RunRequest;
import com.agentforge.agent.dto.RunResponse;
import com.agentforge.agent.service.AgentRunService;
import com.agentforge.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 非流式运行与运行记录（契约 §2.6）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RunController {

    private final AgentRunService runService;

    @PostMapping("/agents/{id}/run")
    @PreAuthorize("hasAuthority('agent:run')")
    public ApiResponse<RunResponse> run(@PathVariable Long id, @RequestBody RunRequest request) {
        return ApiResponse.ok(runService.run(id, request));
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAuthority('agent:read')")
    public ApiResponse<RunResponse> get(@PathVariable Long runId) {
        return ApiResponse.ok(runService.get(runId));
    }

    @PostMapping("/runs/{runId}/cancel")
    @PreAuthorize("hasAuthority('agent:run')")
    public ApiResponse<RunResponse> cancel(@PathVariable Long runId) {
        return ApiResponse.ok(runService.cancel(runId));
    }

    @PostMapping("/runs/{runId}/approve")
    @PreAuthorize("hasAuthority('agent:run')")
    public ApiResponse<RunResponse> approve(@PathVariable Long runId,
                                            @RequestBody ApprovalRequest request) {
        return ApiResponse.ok(runService.approve(runId, request));
    }
}
