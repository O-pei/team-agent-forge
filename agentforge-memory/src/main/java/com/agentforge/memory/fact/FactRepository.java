package com.agentforge.memory.fact;

import java.util.List;

/**
 * 长期记忆存储抽象：InMemory 与 Redis 两种实现由 MemoryConfig 按启动探测结果装配。
 */
public interface FactRepository {

    FactRecord save(Long tenantId, Long userId, String fact, String category, double confidence);

    List<FactRecord> findByScope(Long tenantId, Long userId);

    boolean delete(Long tenantId, Long userId, Long id);
}
