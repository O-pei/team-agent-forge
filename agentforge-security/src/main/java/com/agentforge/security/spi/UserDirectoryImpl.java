package com.agentforge.security.spi;

import com.agentforge.common.context.TenantContext;
import com.agentforge.security.entity.UserEntity;
import com.agentforge.security.repository.UserRepository;
import com.agentforge.security.service.AuthorityService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * UserDirectory 的 JPA 实现：只读查询当前租户用户，返回精简快照，不向其它模块暴露实体与仓储。
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserDirectoryImpl implements UserDirectory {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final AuthorityService authorityService;

    @Override
    public Page pageUsers(String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Specification<UserEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.DEFAULT_TENANT_ID));
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("email"), like),
                        cb.like(root.get("username"), like),
                        cb.like(root.get("nickname"), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        var result = userRepository.findAll(spec, PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt")));
        List<UserBrief> records = result.getContent().stream().map(UserDirectoryImpl::toBrief).toList();
        return new Page(records, result.getTotalElements(), safePage, safeSize);
    }

    @Override
    public UserBrief findUser(long userId) {
        return userRepository.findById(userId).map(UserDirectoryImpl::toBrief).orElse(null);
    }

    @Override
    public boolean hasRole(long userId, String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return false;
        }
        return authorityService.roleCodesOf(userId).contains(roleCode);
    }

    private static UserBrief toBrief(UserEntity user) {
        return new UserBrief(user.getId(), user.getUsername(), user.getEmail(), user.getNickname(), user.getStatus());
    }
}
