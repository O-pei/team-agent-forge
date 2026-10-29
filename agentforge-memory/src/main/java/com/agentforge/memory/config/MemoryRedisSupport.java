package com.agentforge.memory.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redis 可用性探测结果：启动时探测一次，后续所有记忆组件共用同一决策，避免重复连接。
 *
 * <p>无 Redis 时（开发机默认）自动降级为 InMemory 实现，服务启动不受影响。</p>
 */
@Slf4j
public final class MemoryRedisSupport {

    private static final long PROBE_TIMEOUT_MS = 1500L;

    private final StringRedisTemplate template;
    private final boolean available;

    private MemoryRedisSupport(StringRedisTemplate template, boolean available) {
        this.template = template;
        this.available = available;
    }

    public static MemoryRedisSupport probe(StringRedisTemplate template, boolean redisEnabled) {
        if (!redisEnabled) {
            log.info("agentforge.memory.redis-enabled=false，记忆引擎使用 InMemoryMemoryStore（进程内记忆）");
            return new MemoryRedisSupport(null, false);
        }
        if (template == null) {
            log.warn("agentforge.memory.redis-enabled=true 但未装配 RedisTemplate，记忆引擎自动降级为 InMemoryMemoryStore");
            return new MemoryRedisSupport(null, false);
        }
        if (ping(template)) {
            log.info("Redis 连接探测成功，记忆引擎使用 RedisMemoryStore（TTL 7 天，保留最近 200 条）");
            return new MemoryRedisSupport(template, true);
        }
        log.warn("Redis 不可用（agentforge.memory.redis-enabled=true 但连接失败），记忆引擎自动降级为 InMemoryMemoryStore；"
                + "进程重启后会话记忆与长期记忆将丢失。如需持久化请启动 Redis 后重启服务。");
        return new MemoryRedisSupport(null, false);
    }

    public StringRedisTemplate template() {
        return template;
    }

    public boolean available() {
        return available;
    }

    private static boolean ping(StringRedisTemplate template) {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try (var connection = template.getConnectionFactory().getConnection()) {
                return "PONG".equalsIgnoreCase(connection.ping());
            } catch (Exception ignored) {
                return false;
            }
        });
        try {
            return Boolean.TRUE.equals(future.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } catch (Exception ex) {
            future.cancel(true);
            return false;
        }
    }
}
