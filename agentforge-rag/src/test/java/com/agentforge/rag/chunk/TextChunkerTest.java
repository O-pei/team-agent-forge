package com.agentforge.rag.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切分器单测：默认 512/64、段落优先、长段落硬切带重叠、空输入。
 */
class TextChunkerTest {

    @Test
    @DisplayName("空文本返回空列表")
    void emptyTextReturnsNoChunks() {
        assertTrue(TextChunker.split(null).isEmpty());
        assertTrue(TextChunker.split("   \n\n  ").isEmpty());
    }

    @Test
    @DisplayName("短文本按段落聚合且不超过块大小")
    void paragraphsAreMerged() {
        String text = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
        List<String> chunks = TextChunker.split(text, 512, 64);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段"));
        assertTrue(chunks.get(0).contains("第三段"));
    }

    @Test
    @DisplayName("超过块大小时切分为多块，相邻块有重叠")
    void longTextIsSplitWithOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("第").append(i).append("段：AgentForge 是一个多智能体编排平台，支持知识库检索增强。\n\n");
        }
        List<String> chunks = TextChunker.split(sb.toString(), 200, 50);
        assertTrue(chunks.size() > 1, "长文本应切分为多块");
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 200 + 64, "块长度应接近上限: " + chunk.length());
            assertTrue(!chunk.isBlank());
        }
        // 重叠：前一块的尾部内容应出现在下一块开头附近
        String firstTail = chunks.get(0).substring(Math.max(0, chunks.get(0).length() - 40));
        String second = chunks.get(1);
        assertTrue(second.contains(firstTail.substring(Math.max(0, firstTail.length() - 20))),
                "相邻块应保留重叠文本");
    }

    @Test
    @DisplayName("无段落分隔的超长单段也能硬切")
    void singleHugeParagraphIsHardSplit() {
        String text = "x".repeat(1500);
        List<String> chunks = TextChunker.split(text, 512, 64);
        // 512 + 64 重叠：起始 0/448/896/1344 → 4 块，最后一块 156 字符
        assertEquals(4, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 512));
    }

    @Test
    @DisplayName("非法参数抛异常")
    void invalidParamsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TextChunker.split("abc", 10, 10));
        assertThrows(IllegalArgumentException.class, () -> TextChunker.split("abc", 0, 0));
    }
}
