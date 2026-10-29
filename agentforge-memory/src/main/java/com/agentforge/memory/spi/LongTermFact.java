package com.agentforge.memory.spi;

import java.time.Instant;

/**
 * 一条长期记忆（事实/偏好）。
 */
public record LongTermFact(Long id, String fact, String category, double score, Instant createdAt) {
}
