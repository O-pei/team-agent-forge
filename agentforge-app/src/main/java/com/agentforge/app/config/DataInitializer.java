package com.agentforge.app.config;

import com.agentforge.common.context.TenantContext;
import com.agentforge.security.config.AuthProperties;
import com.agentforge.security.entity.RoleEntity;
import com.agentforge.security.entity.UserEntity;
import com.agentforge.security.entity.UserRoleEntity;
import com.agentforge.security.entity.UserStatus;
import com.agentforge.security.repository.RoleRepository;
import com.agentforge.security.repository.UserRepository;
import com.agentforge.security.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * dev 环境默认管理员种子：仅 dev profile 生效，用户已存在则跳过，
 * 密码可由 AGENTFORGE_ADMIN_PASSWORD 覆盖；生产不写入任何默认账号。
 * 同时为管理员幂等初始化 ENTERPRISE 额度账户，V7 迁移未执行时只告警不阻塞启动。
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final AuthProperties authProperties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!authProperties.isSeedAdminEnabled()) {
            log.info("已关闭 dev 管理员种子（agentforge.auth.seed-admin-enabled=false）");
            return;
        }
        if (authProperties.getSeedAdminPassword() == null || authProperties.getSeedAdminPassword().isBlank()) {
            log.warn("未配置 agentforge.auth.seed-admin-password，跳过 dev 管理员创建");
            return;
        }
        String email = authProperties.getSeedAdminEmail();
        UserEntity existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null) {
            log.info("dev 管理员已存在，跳过 seed email={}", email);
            ensureAdminQuota(existing.getId());
            return;
        }
        RoleEntity superAdmin = roleRepository.findByTenantIdAndCode(TenantContext.DEFAULT_TENANT_ID, "SUPER_ADMIN")
                .orElse(null);
        if (superAdmin == null) {
            log.warn("未找到 SUPER_ADMIN 角色（检查 V1 迁移是否已执行），跳过 dev 管理员创建");
            return;
        }
        UserEntity admin = new UserEntity();
        admin.setTenantId(TenantContext.DEFAULT_TENANT_ID);
        admin.setUsername(authProperties.getSeedAdminUsername());
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(authProperties.getSeedAdminPassword()));
        admin.setNickname(authProperties.getSeedAdminNickname());
        admin.setStatus(UserStatus.ACTIVE.name());
        admin.setEmailVerified(true);
        userRepository.save(admin);
        userRoleRepository.save(new UserRoleEntity(admin.getId(), superAdmin.getId()));
        log.info("dev 管理员已创建 email={} username={} roles=[SUPER_ADMIN]（密码来自配置，不在日志中打印）",
                email, admin.getUsername());
        ensureAdminQuota(admin.getId());
    }

    /**
     * 幂等确保管理员有额度账户：已有记录直接返回，无 ENTERPRISE 套餐或表不存在时只告警。
     */
    private void ensureAdminQuota(Long userId) {
        try {
            Integer existing = jdbcTemplate.queryForObject(
                    "select count(*) from ai_user_quota where user_id = ?", Integer.class, userId);
            if (existing != null && existing > 0) {
                return;
            }
            List<Long> tierIds = jdbcTemplate.queryForList(
                    "select id from ai_quota_tier where code = 'ENTERPRISE' and enabled = true", Long.class);
            if (tierIds.isEmpty()) {
                log.warn("未找到 ENTERPRISE 套餐，跳过 dev 管理员额度初始化（检查 V7 迁移是否已执行）");
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            jdbcTemplate.update(
                    "insert into ai_user_quota (tenant_id, user_id, tier_id, granted_tokens, created_at, updated_at, version)"
                            + " values (?, ?, ?, 0, ?, ?, 0)",
                    TenantContext.DEFAULT_TENANT_ID, userId, tierIds.get(0), now, now);
            log.info("dev 管理员额度已初始化 userId={} tier=ENTERPRISE", userId);
        } catch (Exception ex) {
            log.warn("初始化 dev 管理员额度失败（V7 迁移可能未执行）: {}", ex.getMessage());
        }
    }
}
