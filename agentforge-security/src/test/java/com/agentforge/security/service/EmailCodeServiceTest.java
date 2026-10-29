package com.agentforge.security.service;

import com.agentforge.common.exception.BizException;
import com.agentforge.security.auth.InMemoryRateLimiter;
import com.agentforge.security.config.AuthProperties;
import com.agentforge.security.dto.SendEmailCodeResponse;
import com.agentforge.security.entity.EmailVerificationEntity;
import com.agentforge.security.mail.MailSender;
import com.agentforge.security.repository.EmailVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邮箱验证码单测：5 分钟有效、只存哈希、错误次数上限、60s 冷却、dev 返回 devCode。
 */
class EmailCodeServiceTest {

    private EmailVerificationRepository repository;
    private MailSender mailSender;
    private AuthProperties properties;
    private EmailCodeService service;
    private final AtomicReference<EmailVerificationEntity> saved = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        repository = mock(EmailVerificationRepository.class);
        mailSender = mock(MailSender.class);
        properties = new AuthProperties();
        properties.setJwtSecret("unit-test-secret-0123456789-0123456789");
        properties.setEmailCodeTtl(Duration.ofMinutes(5));
        properties.setEmailCodeCooldown(Duration.ofSeconds(60));
        properties.setEmailCodeLength(6);
        properties.setEmailCodeMaxAttempts(3);
        properties.setEmailCodeHourlyLimit(10);
        properties.setEmailCodeIpHourlyLimit(20);
        properties.setExposeCodeInResponse(true);
        service = new EmailCodeService(repository, mailSender, properties, new InMemoryRateLimiter());

        when(repository.save(any(EmailVerificationEntity.class))).thenAnswer(invocation -> {
            EmailVerificationEntity entity = invocation.getArgument(0);
            saved.set(entity);
            return entity;
        });
    }

    @Test
    void shouldSendDevCodeAndStoreOnlyHash() {
        when(repository.existsByEmailAndSceneAndCreatedAtAfter(anyString(), eq("REGISTER"), any(LocalDateTime.class)))
                .thenReturn(false);
        when(repository.countByEmailAndSceneAndCreatedAtAfter(anyString(), eq("REGISTER"), any(LocalDateTime.class)))
                .thenReturn(0L);

        SendEmailCodeResponse response = service.sendCode("Alice@Example.com ", "REGISTER", "127.0.0.1");

        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.scene()).isEqualTo("REGISTER");
        assertThat(response.expiresIn()).isEqualTo(300);
        assertThat(response.devCode()).matches("\\d{6}");
        assertThat(saved.get().getCodeHash())
                .hasSize(64)
                .isNotEqualTo(response.devCode());
        assertThat(saved.get().getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(4));
        verify(mailSender).sendVerificationCode(eq("alice@example.com"), eq("REGISTER"), eq(response.devCode()));
    }

    @Test
    void shouldRejectWhenWithinCooldown() {
        when(repository.existsByEmailAndSceneAndCreatedAtAfter(anyString(), eq("REGISTER"), any(LocalDateTime.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.sendCode("alice@example.com", "REGISTER", "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(40202);
        verify(repository, never()).save(any());
    }

    @Test
    void shouldVerifyCodeAndConsumeIt() {
        EmailVerificationEntity entity = verificationEntity("alice@example.com", "REGISTER", "123456", 0);
        when(repository.findTopByEmailAndSceneAndConsumedFalseOrderByCreatedAtDesc(anyString(), eq("REGISTER")))
                .thenReturn(Optional.of(entity));

        service.verify("alice@example.com", "REGISTER", "123456");

        assertThat(entity.isConsumed()).isTrue();
    }

    @Test
    void shouldCountWrongAttemptsAndInvalidateAfterLimit() {
        EmailVerificationEntity entity = verificationEntity("alice@example.com", "REGISTER", "123456", 2);
        when(repository.findTopByEmailAndSceneAndConsumedFalseOrderByCreatedAtDesc(anyString(), eq("REGISTER")))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.verify("alice@example.com", "REGISTER", "000000"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(40201);

        assertThat(entity.getAttempts()).isEqualTo(3);
        assertThat(entity.isConsumed()).isTrue();
    }

    private EmailVerificationEntity verificationEntity(String email, String scene, String code, int attempts) {
        EmailVerificationEntity entity = new EmailVerificationEntity();
        entity.setEmail(email);
        entity.setScene(scene);
        entity.setCodeHash(serviceHash(email, scene, code));
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(4));
        entity.setConsumed(false);
        entity.setAttempts(attempts);
        return entity;
    }

    /** 与 EmailCodeService#hash 相同的算法，用于构造“正确验证码”实体。 */
    private String serviceHash(String email, String scene, String code) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String raw = properties.getJwtSecret() + "|email-code|" + email + "|" + scene + "|" + code;
            return java.util.HexFormat.of().formatHex(
                    digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
