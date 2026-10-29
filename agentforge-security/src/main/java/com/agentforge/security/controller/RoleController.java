package com.agentforge.security.controller;

import com.agentforge.common.api.ApiResponse;
import com.agentforge.security.dto.CreateRoleRequest;
import com.agentforge.security.dto.PermissionNode;
import com.agentforge.security.dto.RoleVO;
import com.agentforge.security.dto.UpdateRoleRequest;
import com.agentforge.security.service.RoleService;
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
 * 角色与权限接口（契约 §2.3），全部标注权限码。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role:read')")
    public ApiResponse<List<RoleVO>> list() {
        return ApiResponse.ok(roleService.list());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResponse<RoleVO> create(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.ok(roleService.create(request));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResponse<RoleVO> update(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.ok(roleService.update(id, request));
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role:read')")
    public ApiResponse<List<PermissionNode>> permissions() {
        return ApiResponse.ok(roleService.permissionTree());
    }
}
