package com.agentforge.security.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * access token jti 黑名单，登出后立即失效；进程内实现并做过期清理，多实例需改为 Redis。
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
