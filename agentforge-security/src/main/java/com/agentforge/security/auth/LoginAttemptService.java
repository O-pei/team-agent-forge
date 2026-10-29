package com.agentforge.security.auth;

import com.agentforge.security.config.AuthProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败锁定：同账号 15 分钟内失败 5 次即锁定（契约 §2.1）。
 * <p>无 Redis 的进程内实现；多实例部署时需改为 Redis 计数器。</p>
 */
@Component
public class LoginAttemptService {

    private final AuthProperties properties;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(AuthProperties properties) {
        this.properties = properties;
    }

    public boolean isLocked(String account) {
        Attempt attempt = attempts.get(key(account));
        if (attempt == null) {
            return false;
        }
        synchronized (attempt) {
            if (attempt.lockedUntil != null) {
                if (Instant.now().isBefore(attempt.lockedUntil)) {
                    return true;
                }
                attempts.remove(key(account));
            }
            return false;
        }
    }

    public long remainingLockSeconds(String account) {
        Attempt attempt = attempts.get(key(account));
        if (attempt == null) {
            return 0;
        }
        synchronized (attempt) {
            if (attempt.lockedUntil == null || Instant.now().isAfter(attempt.lockedUntil)) {
                return 0;
            }
            return Duration.between(Instant.now(), attempt.lockedUntil).toSeconds();
        }
    }

    public void onFailure(String account) {
        Attempt attempt = attempts.computeIfAbsent(key(account), k -> new Attempt());
        synchronized (attempt) {
            if (attempt.lockedUntil != null && Instant.now().isAfter(attempt.lockedUntil)) {
                attempt.failures = 0;
                attempt.lockedUntil = null;
            }
            attempt.failures++;
            if (attempt.failures >= properties.getLoginMaxFailures()) {
                attempt.lockedUntil = Instant.now().plus(properties.getLoginLockDuration());
            }
        }
    }

    public void onSuccess(String account) {
        attempts.remove(key(account));
    }

    private static String key(String account) {
        return account == null ? "" : account.trim().toLowerCase();
    }

    private static final class Attempt {
        private int failures;
        private Instant lockedUntil;
    }
}
