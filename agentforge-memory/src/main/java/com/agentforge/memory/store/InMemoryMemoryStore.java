package com.agentforge.memory.store;

import com.agentforge.memory.spi.MemoryMessage;
import com.agentforge.memory.spi.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话记忆（Redis 不可用时的兜底实现）：保留最近 200 条、TTL 7 天（惰性清理）。
 *
 * <p>无 Redis 的部署下重启即丢失，属于可接受的降级行为。</p>
 */
public class InMemoryMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMemoryStore.class);

    private static final int MAX_MESSAGES_PER_SESSION = 200;
    private static final Duration TTL = Duration.ofDays(7);

    private final ConcurrentHashMap<String, Deque<MemoryMessage>> sessions = new ConcurrentHashMap<>();

    @Override
    public void appendMessage(String sessionId, MemoryMessage message) {
        if (sessionId == null || message == null) {
            return;
        }
        Deque<MemoryMessage> messages = sessions.computeIfAbsent(sessionId, key -> new LinkedList<>());
        synchronized (messages) {
            messages.addLast(message);
            while (messages.size() > MAX_MESSAGES_PER_SESSION) {
                messages.removeFirst();
            }
            pruneExpired(messages);
        }
        log.debug("写入内存会话记忆: sessionId={}, role={}, size={}",
                sessionId, message.role(), messages.size());
    }

    @Override
    public List<MemoryMessage> getRecentMessages(String sessionId, int limit) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        Deque<MemoryMessage> messages = sessions.get(sessionId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            pruneExpired(messages);
            List<MemoryMessage> snapshot = new ArrayList<>(messages);
            int from = Math.max(0, snapshot.size() - limit);
            return List.copyOf(snapshot.subList(from, snapshot.size()));
        }
    }

    @Override
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    @Override
    public boolean persistent() {
        return false;
    }

    private static void pruneExpired(Deque<MemoryMessage> messages) {
        Instant threshold = Instant.now().minus(TTL);
        while (!messages.isEmpty()) {
            MemoryMessage first = messages.peekFirst();
            if (first == null || first.createdAt() == null || first.createdAt().isBefore(threshold)) {
                messages.removeFirst();
            } else {
                break;
            }
        }
    }
}
