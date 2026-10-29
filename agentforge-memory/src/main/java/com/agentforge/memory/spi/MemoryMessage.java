package com.agentforge.memory.spi;

import java.time.Instant;
import java.util.Map;

/**
 * 一条会话消息（跨模块 DTO）。
 */
public record MemoryMessage(String role, String content, Instant createdAt, Map<String, Object> metadata) {

    public static MemoryMessage user(String content) {
        return new MemoryMessage("user", content, Instant.now(), Map.of());
    }

    public static MemoryMessage assistant(String content) {
        return new MemoryMessage("assistant", content, Instant.now(), Map.of());
    }
}
