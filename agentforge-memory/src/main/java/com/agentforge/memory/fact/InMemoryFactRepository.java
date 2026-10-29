package com.agentforge.memory.fact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内长期记忆存储（无 Redis 时兜底）。
 */
public class InMemoryFactRepository implements FactRepository {

    private final ConcurrentHashMap<String, LinkedHashMap<Long, FactRecord>> scopes = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public FactRecord save(Long tenantId, Long userId, String fact, String category, double confidence) {
        long id = sequence.incrementAndGet();
        FactRecord record = new FactRecord(id, tenantId, userId, fact, category, confidence, Instant.now());
        scopes.computeIfAbsent(scopeKey(tenantId, userId), key -> new LinkedHashMap<>())
                .put(id, record);
        return record;
    }

    @Override
    public List<FactRecord> findByScope(Long tenantId, Long userId) {
        Map<Long, FactRecord> scope = scopes.get(scopeKey(tenantId, userId));
        if (scope == null) {
            return List.of();
        }
        List<FactRecord> records;
        synchronized (scope) {
            records = new ArrayList<>(scope.values());
        }
        records.sort(Comparator.comparing(FactRecord::createdAt).reversed());
        return records;
    }

    @Override
    public boolean delete(Long tenantId, Long userId, Long id) {
        Map<Long, FactRecord> scope = scopes.get(scopeKey(tenantId, userId));
        if (scope == null || id == null) {
            return false;
        }
        synchronized (scope) {
            return scope.remove(id) != null;
        }
    }

    static String scopeKey(Long tenantId, Long userId) {
        return (tenantId == null ? 1L : tenantId) + ":" + (userId == null ? 0L : userId);
    }
}
