package com.agentforge.security.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 认证与账号相关配置，全部落在 {@code agentforge.auth.*} 键块下。
 * <p>生产环境必须通过环境变量覆盖 {@code AGENTFORGE_JWT_SECRET} 与管理员种子密码。</p>
 */
@Data
@ConfigurationProperties(prefix = "agentforge.auth")
public class AuthProperties {

    /** HS256 密钥，UTF-8 字节数必须 >= 32；生产用环境变量覆盖。 */
    private String jwtSecret = "";

    /** access token 有效期，契约 2h。 */
    private Duration accessTokenTtl = Duration.ofHours(2);

    /** refresh token 有效期，契约 7d。 */
    private Duration refreshTokenTtl = Duration.ofDays(7);

    /** 邮箱验证码有效期，技术方案 5min。 */
    private Duration emailCodeTtl = Duration.ofMinutes(5);

    /** 验证码长度。 */
    private int emailCodeLength = 6;

    /** 单个验证码允许的最大错误次数，达到后立即失效。 */
    private int emailCodeMaxAttempts = 5;

    /** 同邮箱 + 场景的发送冷却时间，契约 60s。 */
    private Duration emailCodeCooldown = Duration.ofSeconds(60);

    /** 同邮箱 1 小时内最多发送次数，契约 10 次。 */
    private int emailCodeHourlyLimit = 10;

    /** 同 IP 1 小时内最多发送次数（防刷）。 */
    private int emailCodeIpHourlyLimit = 20;

    /** 登录失败锁定阈值，契约 5 次。 */
    private int loginMaxFailures = 5;

    /** 登录失败锁定时长，契约 15min。 */
    private Duration loginLockDuration = Duration.ofMinutes(15);

    /** SMTP 已配置时为 true，走 JavaMail；否则走日志兜底。 */
    private boolean mailEnabled = false;

    /** 短信通道是否启用（v0.1.0 预留，默认 false）。 */
    private boolean smsEnabled = false;

    /**
     * dev/演示用开关：为 true 时在发送验证码的响应 data.devCode 中回显验证码。
     * 默认关闭，生产必须保持 false。
     */
    private boolean exposeCodeInResponse = false;

    /** 是否在 dev 下自动创建默认管理员（仅当用户不存在时）。 */
    private boolean seedAdminEnabled = true;

    private String seedAdminEmail = "admin@agentforge.dev";

    private String seedAdminUsername = "admin";

    /** 生产/CI 用环境变量覆盖；留空则跳过管理员种子。 */
    private String seedAdminPassword = "";

    private String seedAdminNickname = "系统管理员";
}
