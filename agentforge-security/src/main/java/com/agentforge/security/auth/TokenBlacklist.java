package com.agentforge.security.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * access token jti 黑名单（登出即时失效）。
 * <p>v0.1.0 无 Redis，用进程内存 + 过期清理兜底；多实例部署时需替换为 Redis 实现
 * （TODO: 接入 Redis 后改为 {@code auth:jti:blacklist:{jti}} 并设置与 token 等长的 TTL）。</p>
 */
@Component
public class TokenBlacklist {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    public void blacklist(String jti, Instant expiresAt) {
        if (jti == null) {
            return;
        }
        revoked.put(jti, expiresAt == null ? Instant.now().plusSeconds(900) : expiresAt);
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        Instant expiresAt = revoked.get(jti);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt.isBefore(Instant.now())) {
            revoked.remove(jti);
            return false;
        }
        return true;
    }

    public int size() {
        return revoked.size();
    }
}
