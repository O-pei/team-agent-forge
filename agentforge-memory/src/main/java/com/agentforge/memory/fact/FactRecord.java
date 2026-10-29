package com.agentforge.memory.fact;

import java.time.Instant;

/**
 * 长期记忆持久化记录（内部存储模型），对外转换为 LongTermFact。
 */
public record FactRecord(
        Long id,
        Long tenantId,
        Long userId,
        String fact,
        String category,
        double confidence,
        Instant createdAt) {
}
