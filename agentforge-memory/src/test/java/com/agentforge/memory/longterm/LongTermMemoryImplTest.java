package com.agentforge.memory.longterm;

import com.agentforge.memory.fact.InMemoryFactRepository;
import com.agentforge.memory.spi.LongTermFact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆：中文 bigram 召回排序、置信度兜底、删除。
 */
class LongTermMemoryImplTest {

    private final LongTermMemoryImpl longTermMemory = new LongTermMemoryImpl(new InMemoryFactRepository());

    @Test
    void recallRanksLexicallyRelevantFactFirst() {
        longTermMemory.saveFact(1L, 42L, "用户喜欢喝咖啡，尤其是拿铁", "preference", 1.0);
        longTermMemory.saveFact(1L, 42L, "用户住在上海", "profile", 0.1);

        List<LongTermFact> facts = longTermMemory.recall(1L, 42L, "咖啡", 2);

        assertEquals(2, facts.size());
        assertTrue(facts.get(0).fact().contains("咖啡"));
        assertTrue(facts.get(0).score() > facts.get(1).score());
        assertTrue(facts.get(0).score() <= 1.0);
    }

    @Test
    void blankQueryFallsBackToConfidenceOrder() {
        longTermMemory.saveFact(1L, 42L, "偏好简洁回答", "preference", 0.2);
        longTermMemory.saveFact(1L, 42L, "职业是后端工程师", "profile", 0.9);

        List<LongTermFact> facts = longTermMemory.recall(1L, 42L, "  ", 5);

        assertEquals(2, facts.size());
        assertEquals("职业是后端工程师", facts.get(0).fact());
        assertEquals(0.9, facts.get(0).score(), 0.0001);
    }

    @Test
    void deleteFactRemovesItFromRecall() {
        longTermMemory.saveFact(1L, 42L, "用户喜欢爬山", "preference", 0.8);
        LongTermFact saved = longTermMemory.recall(1L, 42L, "爬山", 1).get(0);

        assertTrue(longTermMemory.deleteFact(1L, 42L, saved.id()));
        assertFalse(longTermMemory.deleteFact(1L, 42L, saved.id()));
        assertTrue(longTermMemory.recall(1L, 42L, "爬山", 5).isEmpty());
    }

    @Test
    void tenantOrUserIsolationKeepsFactsScoped() {
        longTermMemory.saveFact(1L, 42L, "用户喜欢爵士乐", "preference", 1.0);

        assertTrue(longTermMemory.recall(2L, 42L, "爵士", 5).isEmpty());
        assertTrue(longTermMemory.recall(1L, 43L, "爵士", 5).isEmpty());
        assertEquals(1, longTermMemory.recall(1L, 42L, "爵士", 5).size());
    }
}
