package com.agentforge.memory.fact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Redis 长期记忆存储：Hash 结构 agentforge:memory:facts:{tenant}:{user}，TTL 7 天。
 */
@Slf4j
@RequiredArgsConstructor
public class RedisFactRepository implements FactRepository {

    private static final String KEY_PREFIX = "agentforge:memory:facts:";
    private static final String ID_KEY = "agentforge:memory:fact:id";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public FactRecord save(Long tenantId, Long userId, String fact, String category, double confidence) {
        long id = nextId();
        Instant createdAt = Instant.now();
        FactRecord record = new FactRecord(id, tenantId, userId, fact, category, confidence, createdAt);
        try {
            String key = key(tenantId, userId);
            redisTemplate.opsForHash().put(key, String.valueOf(id), serialize(record));
            redisTemplate.expire(key, TTL);
            return record;
        } catch (Exception ex) {
            log.warn("写入 Redis 长期记忆失败: tenantId={}, userId={}, reason={}",
                    tenantId, userId, ex.getMessage());
            return record;
        }
    }

    @Override
    public List<FactRecord> findByScope(Long tenantId, Long userId) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key(tenantId, userId));
            List<FactRecord> records = new ArrayList<>(entries.size());
            for (Object value : entries.values()) {
                FactRecord record = deserialize(String.valueOf(value));
                if (record != null) {
                    records.add(record);
                }
            }
            records.sort(Comparator.comparing(FactRecord::createdAt).reversed());
            return records;
        } catch (Exception ex) {
            log.warn("读取 Redis 长期记忆失败: tenantId={}, userId={}, reason={}",
                    tenantId, userId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean delete(Long tenantId, Long userId, Long id) {
        if (id == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForHash().delete(key(tenantId, userId), String.valueOf(id)) > 0);
        } catch (Exception ex) {
            log.warn("删除 Redis 长期记忆失败: tenantId={}, userId={}, id={}, reason={}",
                    tenantId, userId, id, ex.getMessage());
            return false;
        }
    }

    private long nextId() {
        try {
            Long id = redisTemplate.opsForValue().increment(ID_KEY);
            return id == null ? System.currentTimeMillis() : id;
        } catch (Exception ex) {
            log.warn("生成长期记忆 ID 失败，使用时间戳兜底: {}", ex.getMessage());
            return System.currentTimeMillis();
        }
    }

    private String serialize(FactRecord record) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", record.id());
        payload.put("tenantId", record.tenantId());
        payload.put("userId", record.userId());
        payload.put("fact", record.fact());
        payload.put("category", record.category());
        payload.put("confidence", record.confidence());
        payload.put("createdAt", record.createdAt().toString());
        return objectMapper.writeValueAsString(payload);
    }

    private FactRecord deserialize(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            return new FactRecord(
                    node.path("id").asLong(),
                    node.path("tenantId").asLong(),
                    node.path("userId").asLong(),
                    node.path("fact").asText(null),
                    node.path("category").asText(null),
                    node.path("confidence").asDouble(0.5),
                    Instant.parse(node.path("createdAt").asText(Instant.now().toString())));
        } catch (Exception ex) {
            log.warn("反序列化长期记忆失败，已跳过: {}", ex.getMessage());
            return null;
        }
    }

    private static String key(Long tenantId, Long userId) {
        return KEY_PREFIX + (tenantId == null ? 1L : tenantId) + ":" + (userId == null ? 0L : userId);
    }
}
