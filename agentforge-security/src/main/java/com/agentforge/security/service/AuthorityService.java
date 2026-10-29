package com.agentforge.security.service;

import com.agentforge.security.entity.PermissionEntity;
import com.agentforge.security.entity.RoleEntity;
import com.agentforge.security.entity.RolePermissionEntity;
import com.agentforge.security.entity.UserRoleEntity;
import com.agentforge.security.repository.PermissionRepository;
import com.agentforge.security.repository.RolePermissionRepository;
import com.agentforge.security.repository.RoleRepository;
import com.agentforge.security.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户权限装配：从 DB 取角色编码与权限码（JWT 只做缓存，/auth/me 与 RBAC 校验以 DB 为准）。
 */
@Service
@RequiredArgsConstructor
public class AuthorityService {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;

    /** 用户角色实体列表。 */
    @Transactional(readOnly = true)
    public List<RoleEntity> rolesOf(Long userId) {
        List<Long> roleIds = userRoleRepository.findByUserId(userId).stream()
                .map(link -> link.getId().getRoleId())
                .toList();
        return roleIds.isEmpty() ? List.of() : roleRepository.findAllById(roleIds);
    }

    /** 用户角色编码集合。 */
    @Transactional(readOnly = true)
    public Set<String> roleCodesOf(Long userId) {
        return rolesOf(userId).stream().map(RoleEntity::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 用户全部权限码集合。 */
    @Transactional(readOnly = true)
    public Set<String> permissionCodesOf(Long userId) {
        List<Long> roleIds = userRoleRepository.findByUserId(userId).stream()
                .map(link -> link.getId().getRoleId())
                .toList();
        return permissionCodesOfRoles(roleIds);
    }

    /** 指定角色的权限码集合。 */
    @Transactional(readOnly = true)
    public Set<String> permissionCodesOfRoles(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        List<Long> permissionIds = rolePermissionRepository.findByRoleIdIn(roleIds).stream()
                .map(link -> link.getId().getPermissionId())
                .toList();
        if (permissionIds.isEmpty()) {
            return Set.of();
        }
        return permissionRepository.findAllById(permissionIds).stream()
                .map(PermissionEntity::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 角色 id -> 权限 id 列表。 */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> permissionIdsByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }
        return rolePermissionRepository.findByRoleIdIn(roleIds).stream()
                .collect(Collectors.groupingBy(rp -> rp.getId().getRoleId(),
                        Collectors.mapping(rp -> rp.getId().getPermissionId(), Collectors.toList())));
    }

    /** 用户 id -> 角色编码列表（批量，避免 N+1）。 */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> roleCodesByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<UserRoleEntity> links = userRoleRepository.findByUserIdIn(userIds);
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<Long, RoleEntity> roles = roleRepository.findAllById(
                        links.stream().map(link -> link.getId().getRoleId()).distinct().toList()).stream()
                .collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        return links.stream().collect(Collectors.groupingBy(link -> link.getId().getUserId(),
                Collectors.mapping(link -> {
                    RoleEntity role = roles.get(link.getId().getRoleId());
                    return role == null ? "" : role.getCode();
                }, Collectors.toList())));
    }

}
