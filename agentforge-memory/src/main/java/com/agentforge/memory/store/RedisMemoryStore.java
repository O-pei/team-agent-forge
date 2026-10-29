package com.agentforge.memory.store;

import com.agentforge.memory.spi.MemoryMessage;
import com.agentforge.memory.spi.MemoryStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 会话记忆实现：List 结构 + TTL 7 天 + 只保留最近 200 条。
 */
@Slf4j
@RequiredArgsConstructor
public class RedisMemoryStore implements MemoryStore {

    private static final String KEY_PREFIX = "agentforge:memory:session:";
    private static final Duration TTL = Duration.ofDays(7);
    private static final int MAX_MESSAGES = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void appendMessage(String sessionId, MemoryMessage message) {
        if (sessionId == null || message == null) {
            return;
        }
        try {
            String key = KEY_PREFIX + sessionId;
            redisTemplate.opsForList().rightPush(key, serialize(message));
            redisTemplate.opsForList().trim(key, -MAX_MESSAGES, -1);
            redisTemplate.expire(key, TTL);
        } catch (Exception ex) {
            log.warn("写入 Redis 会话记忆失败: sessionId={}, reason={}", sessionId, ex.getMessage());
        }
    }

    @Override
    public List<MemoryMessage> getRecentMessages(String sessionId, int limit) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        try {
            List<String> raw = redisTemplate.opsForList().range(KEY_PREFIX + sessionId, -limit, -1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<MemoryMessage> messages = new ArrayList<>(raw.size());
            for (String item : raw) {
                MemoryMessage message = deserialize(item);
                if (message != null) {
                    messages.add(message);
                }
            }
            return messages;
        } catch (Exception ex) {
            log.warn("读取 Redis 会话记忆失败: sessionId={}, reason={}", sessionId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public void clearSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception ex) {
            log.warn("清空 Redis 会话记忆失败: sessionId={}, reason={}", sessionId, ex.getMessage());
        }
    }

    @Override
    public boolean persistent() {
        return true;
    }

    private String serialize(MemoryMessage message) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role());
        payload.put("content", message.content());
        payload.put("createdAt", message.createdAt() == null ? Instant.now().toString() : message.createdAt().toString());
        payload.put("metadata", message.metadata() == null ? Map.of() : message.metadata());
        return objectMapper.writeValueAsString(payload);
    }

    private MemoryMessage deserialize(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            String role = node.path("role").asText(null);
            String content = node.path("content").asText(null);
            String createdAtText = node.path("createdAt").asText(null);
            Instant createdAt = createdAtText == null ? Instant.now() : Instant.parse(createdAtText);
            Map<String, Object> metadata = node.has("metadata") && node.get("metadata").isObject()
                    ? objectMapper.convertValue(node.get("metadata"),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class))
                    : Map.of();
            return new MemoryMessage(role, content, createdAt, metadata);
        } catch (Exception ex) {
            log.warn("反序列化会话记忆失败，已跳过该条: {}", ex.getMessage());
            return null;
        }
    }
}
