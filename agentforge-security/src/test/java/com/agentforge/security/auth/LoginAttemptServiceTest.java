package com.agentforge.security.auth;

import com.agentforge.security.config.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录失败锁定单测：5 次失败 15 分钟锁定、成功登录清零。
 */
class LoginAttemptServiceTest {

    private AuthProperties properties;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        properties.setLoginMaxFailures(5);
        properties.setLoginLockDuration(Duration.ofMinutes(15));
        service = new LoginAttemptService(properties);
    }

    @Test
    void shouldLockAccountAfterMaxFailures() {
        for (int i = 0; i < 4; i++) {
            service.onFailure("Alice@Example.com");
        }
        assertThat(service.isLocked("alice@example.com")).isFalse();

        service.onFailure("alice@example.com");

        assertThat(service.isLocked("alice@example.com")).isTrue();
        assertThat(service.remainingLockSeconds("alice@example.com")).isBetween(1L, 15L * 60);
    }

    @Test
    void shouldResetFailuresOnSuccess() {
        for (int i = 0; i < 4; i++) {
            service.onFailure("bob");
        }
        service.onSuccess("bob");

        for (int i = 0; i < 4; i++) {
            service.onFailure("bob");
        }
        assertThat(service.isLocked("bob")).isFalse();
    }

    @Test
    void shouldNotLockUnknownAccount() {
        assertThat(service.isLocked("nobody")).isFalse();
        assertThat(service.remainingLockSeconds("nobody")).isZero();
    }
}
