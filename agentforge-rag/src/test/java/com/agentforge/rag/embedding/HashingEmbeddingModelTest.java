package com.agentforge.rag.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 哈希向量模型单测：维度、确定性、L2 归一化、相似文本相似度更高。
 */
class HashingEmbeddingModelTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel();

    @Test
    @DisplayName("维度固定 1024")
    void dimensions() {
        assertEquals(HashingEmbeddingModel.DIMENSIONS, model.dimensions());
        assertEquals(1024, model.embed("hello").length);
    }

    @Test
    @DisplayName("同文本向量完全一致（确定性）")
    void deterministic() {
        String text = "AgentForge 支持 RAG 知识增强与数据源直连查询。";
        assertArrayEquals(model.embed(text), model.embed(text));
        assertArrayEquals(HashingEmbeddingModel.embedText(text), model.embed(text));
    }

    @Test
    @DisplayName("向量已 L2 归一化（模长 ≈ 1）")
    void normalized() {
        float[] vector = model.embed("多智能体协作 Multi-Agent collaboration 2026");
        double norm = 0.0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        assertEquals(1.0, Math.sqrt(norm), 1e-4);
    }

    @Test
    @DisplayName("相关文本余弦相似度高于无关文本")
    void similarTextsScoreHigher() {
        String base = "AgentForge 使用 Flyway 管理数据库迁移脚本";
        String related = "数据库迁移由 Flyway 脚本完成，AgentForge 启动时校验";
        String unrelated = "今天天气不错适合去公园散步";
        double relatedScore = cosine(model.embed(base), model.embed(related));
        double unrelatedScore = cosine(model.embed(base), model.embed(unrelated));
        assertTrue(relatedScore > unrelatedScore,
                "相关文本相似度应更高: related=" + relatedScore + " unrelated=" + unrelatedScore);
        assertNotEquals(0.0, relatedScore);
    }

    @Test
    @DisplayName("空文本返回非零向量（避免除零）")
    void blankTextSafe() {
        float[] vector = model.embed("");
        assertEquals(1.0f, vector[0], 1e-6);
    }

    @Test
    @DisplayName("批量 call 返回与输入条数一致")
    void batchCall() {
        EmbeddingResponse response = model.call(new EmbeddingRequest(
                List.of("第一条", "第二条", "third"), null));
        assertEquals(3, response.getResults().size());
        for (int i = 0; i < 3; i++) {
            Embedding embedding = response.getResults().get(i);
            assertEquals(i, embedding.getIndex());
            assertEquals(1024, embedding.getOutput().length);
        }
    }

    @Test
    @DisplayName("Document 重载可向量化")
    void documentEmbedding() {
        assertArrayEquals(model.embed("doc text"), model.embed(new Document("doc text")));
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }
}
