package com.agentforge.agent.controller;

import com.agentforge.agent.dto.ToolInfo;
import com.agentforge.agent.tool.AgentToolRegistry;
import com.agentforge.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内置工具清单（契约 §2.6）。
 */
@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolController {

    private final AgentToolRegistry toolRegistry;

    @GetMapping
    @PreAuthorize("hasAuthority('agent:read')")
    public ApiResponse<List<ToolInfo>> list() {
        return ApiResponse.ok(toolRegistry.list());
    }
}
