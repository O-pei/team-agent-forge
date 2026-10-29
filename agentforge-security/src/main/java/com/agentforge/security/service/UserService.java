package com.agentforge.security.service;

import com.agentforge.common.api.PageResult;
import com.agentforge.common.context.TenantContext;
import com.agentforge.common.exception.BizException;
import com.agentforge.security.dto.CreateUserRequest;
import com.agentforge.security.dto.UpdateUserRequest;
import com.agentforge.security.dto.UserVO;
import com.agentforge.security.entity.RoleEntity;
import com.agentforge.security.entity.UserEntity;
import com.agentforge.security.entity.UserRoleEntity;
import com.agentforge.security.entity.UserStatus;
import com.agentforge.security.repository.RoleRepository;
import com.agentforge.security.repository.UserRepository;
import com.agentforge.security.repository.UserRoleRepository;
import com.agentforge.security.util.SecurityUtils;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理（契约 §2.2）：分页查询 / 创建 / 更新（昵称、状态、角色）/ 软禁用 / 重置密码。
 * 写操作由 Controller 上的 {@code @PreAuthorize("hasAuthority('user:write')")} 保护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String DEFAULT_USER_ROLE = "USER";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuthorityService authorityService;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public PageResult<UserVO> page(int page, int size, String keyword, String status) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Specification<UserEntity> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.DEFAULT_TENANT_ID));
            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            }
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("email"), like),
                        cb.like(root.get("username"), like),
                        cb.like(root.get("nickname"), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<UserEntity> result = userRepository.findAll(specification,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, List<String>> rolesByUser = authorityService.roleCodesByUserIds(
                result.getContent().stream().map(UserEntity::getId).toList());
        List<UserVO> records = result.getContent().stream()
                .map(user -> toVO(user, rolesByUser.getOrDefault(user.getId(), List.of())))
                .toList();
        return PageResult.of(records, result.getTotalElements(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public UserVO get(Long id) {
        UserEntity user = requireUser(id);
        return toVO(user, authorityService.roleCodesOf(user.getId()));
    }

    @Transactional
    public UserVO create(CreateUserRequest request) {
        String email = EmailCodeService.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw BizException.conflict("该邮箱已被注册");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw BizException.conflict("该用户名已被占用");
        }
        UserEntity user = new UserEntity();
        user.setTenantId(TenantContext.DEFAULT_TENANT_ID);
        user.setUsername(request.username());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(StringUtils.hasText(request.nickname()) ? request.nickname() : request.username());
        user.setStatus(UserStatus.ACTIVE.name());
        user.setEmailVerified(true);
        userRepository.save(user);
        assignRoles(user.getId(), request.roleIds());
        log.info("管理员创建用户 userId={} username={}", user.getId(), user.getUsername());
        return toVO(user, authorityService.roleCodesOf(user.getId()));
    }

    @Transactional
    public UserVO update(Long id, UpdateUserRequest request) {
        UserEntity user = requireUser(id);
        if (StringUtils.hasText(request.nickname())) {
            user.setNickname(request.nickname());
        }
        if (StringUtils.hasText(request.status())) {
            applyStatus(user, request.status().trim().toUpperCase());
        }
        userRepository.save(user);
        if (request.roleIds() != null) {
            assignRoles(user.getId(), request.roleIds());
        }
        return toVO(user, authorityService.roleCodesOf(user.getId()));
    }

    /** 软禁用：不物理删除，保留登录日志与审计链路（契约 §2.2 DELETE）。 */
    @Transactional
    public void disable(Long id) {
        UserEntity user = requireUser(id);
        applyStatus(user, UserStatus.DISABLED.name());
        userRepository.save(user);
        log.info("用户已被禁用 userId={}", user.getId());
    }

    /** 管理员重置密码：同时吊销该用户全部 refresh token。 */
    @Transactional
    public void resetPassword(Long id, String rawPassword) {
        UserEntity user = requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
        refreshTokenService.revokeAll(user.getId());
        log.info("管理员重置用户密码 userId={}，已吊销全部 refresh token", user.getId());
    }

    private void applyStatus(UserEntity user, String status) {
        if (UserStatus.DISABLED.name().equals(status)
                && user.getId().equals(SecurityUtils.currentUserIdOrThrow())) {
            throw BizException.badRequest("不能禁用当前登录账号");
        }
        if (!UserStatus.ACTIVE.name().equals(status) && !UserStatus.DISABLED.name().equals(status)) {
            throw BizException.badRequest("status 仅支持 ACTIVE/DISABLED");
        }
        user.setStatus(status);
    }

    /** 覆盖式分配角色；为空时回落到内置 USER 角色。 */
    private void assignRoles(Long userId, List<Long> roleIds) {
        userRoleRepository.deleteByUserId(userId);
        List<Long> targetRoleIds = roleIds == null || roleIds.isEmpty()
                ? List.of(defaultUserRoleId())
                : roleIds.stream().distinct().toList();
        List<RoleEntity> roles = roleRepository.findAllById(targetRoleIds);
        if (roles.size() != targetRoleIds.size()) {
            throw BizException.badRequest("包含不存在的角色 id");
        }
        for (RoleEntity role : roles) {
            userRoleRepository.save(new UserRoleEntity(userId, role.getId()));
        }
    }

    private Long defaultUserRoleId() {
        return roleRepository.findByTenantIdAndCode(TenantContext.DEFAULT_TENANT_ID, DEFAULT_USER_ROLE)
                .map(RoleEntity::getId)
                .orElseThrow(() -> BizException.badRequest("默认角色 USER 未初始化，请检查 V1 迁移"));
    }

    private UserEntity requireUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
    }

    private UserVO toVO(UserEntity user, Collection<String> roles) {
        return new UserVO(user.getId(), user.getUsername(), user.getEmail(), user.getNickname(),
                user.getAvatarUrl(), user.getPhone(), user.getStatus(), user.isEmailVerified(),
                roles.stream().filter(StringUtils::hasText).toList(), user.getLastLoginAt(), user.getCreatedAt());
    }
}
