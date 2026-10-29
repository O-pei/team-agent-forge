package com.agentforge.security.service;

import com.agentforge.common.api.ErrorCode;
import com.agentforge.common.context.TenantContext;
import com.agentforge.common.exception.BizException;
import com.agentforge.security.auth.AuthErrorCodes;
import com.agentforge.security.auth.JwtService;
import com.agentforge.security.auth.LoginAttemptService;
import com.agentforge.security.auth.TokenBlacklist;
import com.agentforge.security.dto.CurrentUserResponse;
import com.agentforge.security.dto.LoginRequest;
import com.agentforge.security.dto.LoginResponse;
import com.agentforge.security.dto.MenuItem;
import com.agentforge.security.dto.PasswordResetRequest;
import com.agentforge.security.dto.RegisterRequest;
import com.agentforge.security.dto.SendEmailCodeRequest;
import com.agentforge.security.dto.SendEmailCodeResponse;
import com.agentforge.security.dto.UserInfo;
import com.agentforge.security.entity.CodeScene;
import com.agentforge.security.entity.LoginLogEntity;
import com.agentforge.security.entity.RoleEntity;
import com.agentforge.security.entity.UserEntity;
import com.agentforge.security.entity.UserRoleEntity;
import com.agentforge.security.entity.UserStatus;
import com.agentforge.security.repository.LoginLogRepository;
import com.agentforge.security.repository.RoleRepository;
import com.agentforge.security.repository.UserRepository;
import com.agentforge.security.repository.UserRoleRepository;
import com.agentforge.security.sms.PhoneLoginService;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 认证核心服务：注册 / 登录 / 刷新 / 登出 / 当前用户 / 找回密码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String DEFAULT_USER_ROLE = "USER";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuthorityService authorityService;
    private final EmailCodeService emailCodeService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final LoginLogRepository loginLogRepository;
    private final TokenBlacklist tokenBlacklist;
    private final PhoneLoginService phoneLoginService;

    /** 发送邮箱验证码。 */
    public SendEmailCodeResponse sendEmailCode(SendEmailCodeRequest request, String ip) {
        return emailCodeService.sendCode(request.email(), request.scene(), ip);
    }

    /** 注册：先校验验证码，再创建用户并绑定默认 USER 角色，注册成功直接返回双 token。 */
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        String email = EmailCodeService.normalize(request.email());
        emailCodeService.verify(email, CodeScene.REGISTER.name(), request.code());
        if (userRepository.existsByEmail(email)) {
            throw BizException.conflict("该邮箱已被注册");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw BizException.conflict("该用户名已被占用");
        }
        Long tenantId = TenantContext.DEFAULT_TENANT_ID;
        UserEntity user = new UserEntity();
        user.setTenantId(tenantId);
        user.setUsername(request.username());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.username());
        user.setStatus(UserStatus.ACTIVE.name());
        user.setEmailVerified(true);
        userRepository.save(user);

        RoleEntity defaultRole = roleRepository.findByTenantIdAndCode(tenantId, DEFAULT_USER_ROLE)
                .orElseThrow(() -> new BizException(ErrorCode.INTERNAL_ERROR, "默认角色 USER 未初始化，请检查 V1 迁移"));
        userRoleRepository.save(new UserRoleEntity(user.getId(), defaultRole.getId()));

        saveLoginLog(user.getId(), user.getUsername(), true, "注册成功", null, null);
        log.info("用户注册成功 userId={} username={}", user.getId(), user.getUsername());
        return issueTokens(user, null, null);
    }

    /** 登录：账号支持邮箱或用户名；5 次失败锁定 15 分钟。 */
    @Transactional
    public LoginResponse login(LoginRequest request, String userAgent, String ip) {
        String account = request.account() == null ? "" : request.account().trim();
        if (loginAttemptService.isLocked(account)) {
            long seconds = loginAttemptService.remainingLockSeconds(account);
            saveLoginLog(null, account, false, "账号锁定", ip, userAgent);
            throw new BizException(AuthErrorCodes.ACCOUNT_LOCKED,
                    "账号已锁定，请 " + Math.max(seconds, 1) + " 秒后重试");
        }
        UserEntity user = findAccount(account).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.onFailure(account);
            saveLoginLog(user == null ? null : user.getId(), account, false, "账号或密码错误", ip, userAgent);
            throw BizException.unauthorized("账号或密码错误");
        }
        if (!user.isActive()) {
            saveLoginLog(user.getId(), user.getUsername(), false, "账号已禁用", ip, userAgent);
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        }
        if (!user.isEmailVerified()) {
            saveLoginLog(user.getId(), user.getUsername(), false, "邮箱未验证", ip, userAgent);
            throw new BizException(AuthErrorCodes.EMAIL_NOT_VERIFIED, "邮箱未验证，请先完成邮箱验证");
        }
        loginAttemptService.onSuccess(account);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        saveLoginLog(user.getId(), user.getUsername(), true, null, ip, userAgent);
        log.info("用户登录成功 userId={} username={}", user.getId(), user.getUsername());
        return issueTokens(user, userAgent, ip);
    }

    /** 刷新：旧 refresh token 一次性消费并轮换。 */
    @Transactional
    public LoginResponse refresh(String rawRefreshToken, String userAgent, String ip) {
        Long userId = refreshTokenService.consume(rawRefreshToken);
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(AuthErrorCodes.REFRESH_TOKEN_INVALID, "refresh token 无效或已吊销"));
        if (!user.isActive()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        }
        return issueTokens(user, userAgent, ip);
    }

    /** 登出：access token jti 进黑名单；有 refreshToken 则吊销之，否则吊销该用户全部 refresh。 */
    @Transactional
    public void logout(Long userId, String accessToken, String refreshToken) {
        if (accessToken != null) {
            try {
                JwtService.AccessClaims claims = jwtService.parseAccessToken(accessToken);
                tokenBlacklist.blacklist(claims.jti(), claims.expiresAt());
            } catch (JwtException | IllegalArgumentException ignored) {
                // token 已过期/非法时无需再拉黑，登出仍然幂等成功
            }
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.revoke(refreshToken);
        } else {
            refreshTokenService.revokeAll(userId);
        }
    }

    /** 当前用户：用户信息 + 角色 + 权限 + 菜单，角色/权限实时读 DB。 */
    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> BizException.unauthorized("用户不存在或已被删除"));
        Set<String> roles = authorityService.roleCodesOf(userId);
        Set<String> permissions = authorityService.permissionCodesOf(userId);
        return new CurrentUserResponse(toUserInfo(user), roles, permissions, buildMenus(permissions));
    }

    /** 忘记密码：无论邮箱是否存在都返回成功，避免账号枚举；验证码发送失败仍抛 40202。 */
    @Transactional
    public void forgotPassword(String email) {
        String normalized = EmailCodeService.normalize(email);
        userRepository.findByEmail(normalized).ifPresent(user -> {
            emailCodeService.sendCode(normalized, CodeScene.RESET_PASSWORD.name(), null);
            log.info("找回密码验证码已发送 email={}", EmailCodeService.maskEmail(normalized));
        });
    }

    /** 重置密码：校验验证码后更新密码并吊销全部 refresh token。 */
    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String email = EmailCodeService.normalize(request.email());
        emailCodeService.verify(email, CodeScene.RESET_PASSWORD.name(), request.code());
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BizException(AuthErrorCodes.EMAIL_CODE_INVALID, "验证码错误或已过期"));
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        refreshTokenService.revokeAll(user.getId());
        log.info("用户密码已重置 userId={}", user.getId());
    }

    /** 短信登录（预留）：未配置通道时返回 50300 sms-not-configured。 */
    public void sendSmsCode(String phone) {
        phoneLoginService.sendLoginCode(phone);
    }

    private java.util.Optional<UserEntity> findAccount(String account) {
        if (account.contains("@")) {
            return userRepository.findByEmail(account.toLowerCase());
        }
        return userRepository.findByUsername(account);
    }

    private LoginResponse issueTokens(UserEntity user, String userAgent, String ip) {
        Set<String> roles = authorityService.roleCodesOf(user.getId());
        Set<String> permissions = authorityService.permissionCodesOf(user.getId());
        String accessToken = jwtService.createAccessToken(user.getId(), user.getUsername(), user.getTenantId(),
                roles, permissions);
        String refreshToken = refreshTokenService.issue(user.getId(), userAgent, ip);
        return new LoginResponse(accessToken, refreshToken, jwtService.accessTokenTtlSeconds(), toUserInfo(user));
    }

    private UserInfo toUserInfo(UserEntity user) {
        return new UserInfo(user.getId(), user.getUsername(), user.getEmail(), user.getNickname(), user.getAvatarUrl());
    }

    private void saveLoginLog(Long userId, String username, boolean success, String reason, String ip, String userAgent) {
        try {
            LoginLogEntity entity = new LoginLogEntity();
            entity.setUserId(userId);
            entity.setUsername(truncate(username, 64));
            entity.setSuccess(success);
            entity.setReason(reason);
            entity.setIp(truncate(ip, 64));
            entity.setUserAgent(truncate(userAgent, 255));
            loginLogRepository.save(entity);
        } catch (Exception ex) {
            log.warn("写入登录日志失败 username={} error={}", username, ex.getMessage());
        }
    }

    private static List<MenuItem> buildMenus(Set<String> permissions) {
        List<MenuItem> menus = new ArrayList<>();
        menus.add(new MenuItem("dashboard", "概览", "/dashboard", "Odometer", null));
        if (permissions.contains("agent:read")) {
            menus.add(new MenuItem("agent", "Agent 管理", "/agents", "Cpu", "agent:read"));
        }
        if (permissions.contains("rag:read")) {
            menus.add(new MenuItem("rag", "知识库", "/knowledge", "Collection", "rag:read"));
        }
        if (permissions.contains("datasource:read")) {
            menus.add(new MenuItem("datasource", "数据源", "/datasources", "Coin", "datasource:read"));
        }
        if (permissions.contains("model:read")) {
            menus.add(new MenuItem("model", "模型配置", "/models", "Setting", "model:read"));
        }
        if (permissions.contains("memory:read")) {
            menus.add(new MenuItem("memory", "记忆管理", "/memory", "Memo", "memory:read"));
        }
        if (permissions.contains("audit:read")) {
            menus.add(new MenuItem("audit", "审计日志", "/audit", "DataAnalysis", "audit:read"));
        }
        if (permissions.contains("user:read")) {
            menus.add(new MenuItem("user", "用户管理", "/users", "User", "user:read"));
        }
        if (permissions.contains("role:read")) {
            menus.add(new MenuItem("role", "角色权限", "/roles", "Lock", "role:read"));
        }
        if (permissions.contains("quota:read")) {
            menus.add(new MenuItem("quota", "额度管理", "/admin/quota", "Coin", "quota:read"));
        }
        return menus;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
