package com.agentforge.security.service;

import com.agentforge.common.context.TenantContext;
import com.agentforge.common.exception.BizException;
import com.agentforge.security.dto.CreateRoleRequest;
import com.agentforge.security.dto.PermissionNode;
import com.agentforge.security.dto.RoleVO;
import com.agentforge.security.dto.UpdateRoleRequest;
import com.agentforge.security.entity.PermissionEntity;
import com.agentforge.security.entity.RoleEntity;
import com.agentforge.security.entity.RolePermissionEntity;
import com.agentforge.security.repository.PermissionRepository;
import com.agentforge.security.repository.RolePermissionRepository;
import com.agentforge.security.repository.RoleRepository;
import com.agentforge.security.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色与权限管理（契约 §2.3）：角色 CRUD（内置角色不可删）、权限树。
 * 写操作由 Controller 上的 {@code @PreAuthorize("hasAuthority('role:write')")} 保护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private static final Map<String, String> RESOURCE_NAMES = Map.of(
            "user", "用户管理",
            "role", "角色与权限",
            "audit", "审计日志",
            "model", "模型配置",
            "agent", "Agent 管理",
            "rag", "知识库",
            "datasource", "数据源",
            "memory", "记忆管理");

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuthorityService authorityService;

    @Transactional(readOnly = true)
    public List<RoleVO> list() {
        List<RoleEntity> roles = roleRepository.findAllByTenantIdOrderByIdAsc(TenantContext.DEFAULT_TENANT_ID);
        Map<Long, List<Long>> permissionIds = authorityService.permissionIdsByRoleIds(
                roles.stream().map(RoleEntity::getId).toList());
        return roles.stream()
                .map(role -> toVO(role, permissionIds.getOrDefault(role.getId(), List.of())))
                .toList();
    }

    @Transactional
    public RoleVO create(CreateRoleRequest request) {
        Long tenantId = TenantContext.DEFAULT_TENANT_ID;
        if (roleRepository.existsByTenantIdAndCode(tenantId, request.code())) {
            throw BizException.conflict("角色编码已存在: " + request.code());
        }
        RoleEntity role = new RoleEntity();
        role.setTenantId(tenantId);
        role.setCode(request.code());
        role.setName(request.name());
        role.setDescription(request.description());
        role.setBuiltin(false);
        roleRepository.save(role);
        replacePermissions(role.getId(), request.permissionIds());
        log.info("创建角色 roleId={} code={}", role.getId(), role.getCode());
        return toVO(role, currentPermissionIds(role.getId()));
    }

    @Transactional
    public RoleVO update(Long id, UpdateRoleRequest request) {
        RoleEntity role = requireRole(id);
        if (StringUtils.hasText(request.name())) {
            role.setName(request.name());
        }
        if (request.description() != null) {
            role.setDescription(request.description());
        }
        roleRepository.save(role);
        if (request.permissionIds() != null) {
            if (role.isBuiltin()) {
                throw BizException.badRequest("内置角色的权限不允许修改");
            }
            replacePermissions(role.getId(), request.permissionIds());
        }
        return toVO(role, currentPermissionIds(role.getId()));
    }

    @Transactional
    public void delete(Long id) {
        RoleEntity role = requireRole(id);
        if (role.isBuiltin()) {
            throw BizException.badRequest("内置角色不允许删除: " + role.getCode());
        }
        if (userRoleRepository.countByIdRoleId(id) > 0) {
            throw BizException.conflict("该角色仍有关联用户，请先解除分配");
        }
        rolePermissionRepository.deleteByRoleId(id);
        roleRepository.delete(role);
        log.info("删除角色 roleId={} code={}", id, role.getCode());
    }

    /** 权限树：按 resource 分组，叶子为具体权限码。 */
    @Transactional(readOnly = true)
    public List<PermissionNode> permissionTree() {
        List<PermissionEntity> permissions = permissionRepository.findAllByOrderByResourceAscActionAsc();
        Map<String, List<PermissionEntity>> grouped = permissions.stream()
                .collect(Collectors.groupingBy(PermissionEntity::getResource, LinkedHashMap::new, Collectors.toList()));
        List<PermissionNode> tree = new ArrayList<>();
        grouped.forEach((resource, items) -> {
            List<PermissionNode> children = items.stream()
                    .map(item -> new PermissionNode(item.getCode(), item.getName(), item.getResource(),
                            item.getAction(), item.getDescription(), List.of()))
                    .toList();
            tree.add(new PermissionNode(resource, RESOURCE_NAMES.getOrDefault(resource, resource),
                    resource, null, null, children));
        });
        return tree;
    }

    private void replacePermissions(Long roleId, List<Long> permissionIds) {
        rolePermissionRepository.deleteByRoleId(roleId);
        if (permissionIds == null || permissionIds.isEmpty()) {
            return;
        }
        List<Long> distinctIds = permissionIds.stream().distinct().toList();
        List<PermissionEntity> permissions = permissionRepository.findAllById(distinctIds);
        if (permissions.size() != distinctIds.size()) {
            throw BizException.badRequest("包含不存在的权限 id");
        }
        for (PermissionEntity permission : permissions) {
            rolePermissionRepository.save(new RolePermissionEntity(roleId, permission.getId()));
        }
    }

    private List<Long> currentPermissionIds(Long roleId) {
        return rolePermissionRepository.findByRoleId(roleId).stream()
                .map(link -> link.getId().getPermissionId())
                .toList();
    }

    private RoleEntity requireRole(Long id) {
        return roleRepository.findById(id).orElseThrow(() -> BizException.notFound("角色不存在"));
    }

    private RoleVO toVO(RoleEntity role, List<Long> permissionIds) {
        return new RoleVO(role.getId(), role.getCode(), role.getName(), role.getDescription(), role.isBuiltin(),
                permissionIds, role.getCreatedAt());
    }
}
