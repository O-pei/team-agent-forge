package com.agentforge.datasource.web;

import com.agentforge.common.api.ApiResponse;
import com.agentforge.datasource.dto.SandboxExecuteRequest;
import com.agentforge.datasource.dto.SandboxProviderInfo;
import com.agentforge.datasource.sandbox.SandboxService;
import com.agentforge.datasource.spi.SandboxResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 代码沙箱接口（契约 docs/04-API契约.md §4）。
 */
@RestController
@RequestMapping("/api/v1/sandbox")
@RequiredArgsConstructor
public class SandboxController {

    private final SandboxService sandboxService;

    @PostMapping("/execute")
    @PreAuthorize("hasAuthority('datasource:query')")
    public ApiResponse<SandboxResult> execute(@Valid @RequestBody SandboxExecuteRequest request) {
        return ApiResponse.ok(sandboxService.execute(request.toSandboxRequest()));
    }

    @GetMapping("/providers")
    @PreAuthorize("hasAuthority('datasource:read')")
    public ApiResponse<List<SandboxProviderInfo>> providers() {
        return ApiResponse.ok(sandboxService.providers());
    }
}
