package com.agentforge.security.service;

import com.agentforge.security.auth.AuthErrorCodes;
import com.agentforge.security.auth.InMemoryRateLimiter;
import com.agentforge.security.config.AuthProperties;
import com.agentforge.security.dto.SendEmailCodeResponse;
import com.agentforge.security.entity.CodeScene;
import com.agentforge.security.entity.EmailVerificationEntity;
import com.agentforge.security.mail.MailSender;
import com.agentforge.security.repository.EmailVerificationRepository;
import com.agentforge.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 邮箱验证码：生成 / 限流 / 发送 / 校验。
 *
 * <p>安全约束（契约 §2.1 + 技术方案 §4.2）：</p>
 * <ul>
 *   <li>5 分钟有效；</li>
 *   <li>同邮箱 + 场景 60s 冷却、1 小时最多 10 次；同 IP 1 小时最多 20 次；</li>
 *   <li>单个验证码最多 5 次错误尝试，超出即失效；</li>
 *   <li>数据库只存验证码哈希（pepper 来自 jwt-secret），不存明文。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeService {

    private static final Duration HOURLY_WINDOW = Duration.ofHours(1);

    private final EmailVerificationRepository emailVerificationRepository;
    private final MailSender mailSender;
    private final AuthProperties properties;
    private final InMemoryRateLimiter rateLimiter;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public SendEmailCodeResponse sendCode(String email, String sceneValue, String ip) {
        String normalizedEmail = normalize(email);
        CodeScene scene = CodeScene.from(sceneValue);
        LocalDateTime now = LocalDateTime.now();

        boolean coolingDown = emailVerificationRepository.existsByEmailAndSceneAndCreatedAtAfter(
                normalizedEmail, scene.name(), now.minus(properties.getEmailCodeCooldown()));
        if (coolingDown) {
            throw new BizException(AuthErrorCodes.EMAIL_CODE_TOO_FREQUENT,
                    "验证码发送过于频繁，请 " + properties.getEmailCodeCooldown().toSeconds() + " 秒后重试");
        }
        long hourlyCount = emailVerificationRepository.countByEmailAndSceneAndCreatedAtAfter(
                normalizedEmail, scene.name(), now.minus(HOURLY_WINDOW));
        if (hourlyCount >= properties.getEmailCodeHourlyLimit()) {
            throw new BizException(AuthErrorCodes.EMAIL_CODE_TOO_FREQUENT, "该邮箱 1 小时内验证码发送次数已达上限");
        }
        String ipKey = "email-code:ip:" + (ip == null ? "unknown" : ip);
        if (!rateLimiter.tryAcquire(ipKey, properties.getEmailCodeIpHourlyLimit(), HOURLY_WINDOW)) {
            throw new BizException(AuthErrorCodes.EMAIL_CODE_TOO_FREQUENT, "当前 IP 验证码请求过于频繁");
        }

        String code = generateCode(properties.getEmailCodeLength());
        EmailVerificationEntity entity = new EmailVerificationEntity();
        entity.setEmail(normalizedEmail);
        entity.setScene(scene.name());
        entity.setCodeHash(hash(normalizedEmail, scene.name(), code));
        entity.setExpiresAt(now.plus(properties.getEmailCodeTtl()));
        entity.setConsumed(false);
        entity.setAttempts(0);
        emailVerificationRepository.save(entity);

        mailSender.sendVerificationCode(normalizedEmail, scene.name(), code);
        log.info("邮箱验证码已生成 email={} scene={} expiresIn={}s", maskEmail(normalizedEmail), scene,
                properties.getEmailCodeTtl().toSeconds());

        String devCode = properties.isExposeCodeInResponse() ? code : null;
        return new SendEmailCodeResponse(normalizedEmail, scene.name(),
                properties.getEmailCodeTtl().toSeconds(), properties.getEmailCodeCooldown().toSeconds(), devCode);
    }

    /**
     * 校验验证码：成功后立即标记 consumed；失败累计 attempts，达到上限后作废。
     */
    @Transactional
    public void verify(String email, String sceneValue, String code) {
        String normalizedEmail = normalize(email);
        CodeScene scene = CodeScene.from(sceneValue);
        EmailVerificationEntity entity = emailVerificationRepository
                .findTopByEmailAndSceneAndConsumedFalseOrderByCreatedAtDesc(normalizedEmail, scene.name())
                .orElseThrow(() -> new BizException(AuthErrorCodes.EMAIL_CODE_INVALID, "验证码错误或已过期"));

        LocalDateTime now = LocalDateTime.now();
        if (entity.isConsumed() || entity.getExpiresAt().isBefore(now)) {
            throw new BizException(AuthErrorCodes.EMAIL_CODE_INVALID, "验证码错误或已过期");
        }
        if (entity.getAttempts() >= properties.getEmailCodeMaxAttempts()) {
            throw new BizException(AuthErrorCodes.EMAIL_CODE_INVALID, "验证码尝试次数过多，请重新获取");
        }
        if (!constantTimeEquals(hash(normalizedEmail, scene.name(), code), entity.getCodeHash())) {
            entity.setAttempts(entity.getAttempts() + 1);
            if (entity.getAttempts() >= properties.getEmailCodeMaxAttempts()) {
                entity.setConsumed(true);
            }
            emailVerificationRepository.save(entity);
            throw new BizException(AuthErrorCodes.EMAIL_CODE_INVALID, "验证码错误或已过期");
        }
        entity.setConsumed(true);
        emailVerificationRepository.save(entity);
    }

    private String generateCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(secureRandom.nextInt(10));
        }
        return builder.toString();
    }

    private String hash(String email, String scene, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = properties.getJwtSecret() + "|email-code|" + email + "|" + scene + "|" + code;
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    static String normalize(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        return email.trim().toLowerCase();
    }

    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
