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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * dev 环境默认管理员种子：仅 dev profile 生效，用户已存在则跳过，
 * 密码可由 AGENTFORGE_ADMIN_PASSWORD 覆盖；生产不写入任何默认账号。
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
        if (userRepository.existsByEmail(email)) {
            log.info("dev 管理员已存在，跳过 seed email={}", email);
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
    }
}
