package com.agentforge.security.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程内滑动窗口限流单测（无 Redis 时的降级实现）。
 */
class InMemoryRateLimiterTest {

    @Test
    void shouldAllowUpToLimitWithinWindow() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();

        assertThat(limiter.tryAcquire("ip:1.2.3.4", 2, Duration.ofHours(1))).isTrue();
        assertThat(limiter.tryAcquire("ip:1.2.3.4", 2, Duration.ofHours(1))).isTrue();
        assertThat(limiter.tryAcquire("ip:1.2.3.4", 2, Duration.ofHours(1))).isFalse();
        assertThat(limiter.tryAcquire("ip:5.6.7.8", 2, Duration.ofHours(1))).isTrue();
    }

    @Test
    void shouldSlideWindowAndReset() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();
        assertThat(limiter.tryAcquire("k", 1, Duration.ofMillis(1))).isTrue();
        try {
            Thread.sleep(5);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertThat(limiter.tryAcquire("k", 1, Duration.ofMillis(1))).isTrue();

        limiter.reset("k");
        assertThat(limiter.tryAcquire("k", 1, Duration.ofMillis(1))).isTrue();
    }
}
