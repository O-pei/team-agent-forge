package com.agentforge.audit.controller;

import com.agentforge.audit.api.ModelConfigRequest;
import com.agentforge.audit.api.ModelConfigResponse;
import com.agentforge.audit.api.ModelTestResponse;
import com.agentforge.audit.service.ModelConfigService;
import com.agentforge.common.api.ApiResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Model configuration endpoints (contract 2.2). Read requires model:read, write requires model:write.
 */
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('model:read')")
public class ModelController {

    private final ModelConfigService modelConfigService;

    @GetMapping
    public ApiResponse<List<ModelConfigResponse>> list() {
        return ApiResponse.ok(modelConfigService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ModelConfigResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(modelConfigService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('model:write')")
    public ApiResponse<ModelConfigResponse> create(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(modelConfigService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('model:write')")
    public ApiResponse<ModelConfigResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(modelConfigService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('model:write')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        modelConfigService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('model:write')")
    public ApiResponse<ModelTestResponse> test(@PathVariable Long id) {
        return ApiResponse.ok(modelConfigService.test(id));
    }
}
