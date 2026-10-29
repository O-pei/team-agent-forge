package com.agentforge.memory.store;

import com.agentforge.memory.spi.MemoryMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InMemory 会话记忆：容量上限 200、按时间顺序返回、清空。
 */
class InMemoryMemoryStoreTest {

    private final InMemoryMemoryStore store = new InMemoryMemoryStore();

    @Test
    void keepsOnlyLatest200MessagesAndReturnsChronologicalOrder() {
        for (int i = 0; i < 205; i++) {
            store.appendMessage("session-1", MemoryMessage.user("m" + i));
        }

        List<MemoryMessage> messages = store.getRecentMessages("session-1", 500);

        assertEquals(200, messages.size());
        assertEquals("m5", messages.get(0).content());
        assertEquals("m204", messages.get(messages.size() - 1).content());
        assertFalse(store.persistent());
    }

    @Test
    void getRecentMessagesRespectsLimit() {
        for (int i = 0; i < 10; i++) {
            store.appendMessage("session-1", MemoryMessage.user("m" + i));
        }

        List<MemoryMessage> recent = store.getRecentMessages("session-1", 3);

        assertEquals(3, recent.size());
        assertEquals("m7", recent.get(0).content());
        assertEquals("m9", recent.get(2).content());
    }

    @Test
    void clearSessionRemovesMessages() {
        store.appendMessage("session-1", MemoryMessage.user("hello"));

        store.clearSession("session-1");

        assertTrue(store.getRecentMessages("session-1", 10).isEmpty());
        assertTrue(store.getRecentMessages("unknown", 10).isEmpty());
    }
}
