package com.agentforge.memory.spi;

import java.util.List;

/**
 * 会话记忆存储（工作记忆）。Redis 不可用时由 InMemory 实现兜底。
 */
public interface MemoryStore {

    void appendMessage(String sessionId, MemoryMessage message);

    List<MemoryMessage> getRecentMessages(String sessionId, int limit);

    void clearSession(String sessionId);

    /** 是否为持久化实现（Redis=true，内存=false），用于健康检查与文档说明。 */
    default boolean persistent() {
        return false;
    }
}
