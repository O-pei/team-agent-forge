package com.agentforge.rag.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线确定性哈希向量模型（不依赖任何外部 API Key）。
 *
 * <p>算法：分词（英文/数字按词，CJK 按单字 + 相邻二字 bigram）→ FNV-1a 哈希映射到 1024 维 →
 * 按哈希符号累加 → L2 归一化。同输入永远得到同向量（字面相似 → 余弦相似度相近），
 * 适合 v0.1 离线演示与单测；后续可替换为真实 embedding 模型（接口不变）。</p>
 */
@Slf4j
@Component
@Primary
public class HashingEmbeddingModel implements EmbeddingModel {

    /** 向量维度；切换模型时需与 kb_knowledge_base.embedding_dimension 保持一致。 */
    public static final int DIMENSIONS = 1024;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    @Override
    public float[] embed(String text) {
        return embedText(text);
    }

    @Override
    public float[] embed(Document document) {
        return embedText(document == null ? null : document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request == null ? null : request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>();
        if (inputs != null) {
            for (int i = 0; i < inputs.size(); i++) {
                embeddings.add(new Embedding(embedText(inputs.get(i)), i));
            }
        }
        return new EmbeddingResponse(embeddings);
    }

    /**
     * 计算文本向量，保证确定性：同文本多次调用结果完全一致。
     */
    public static float[] embedText(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            vector[0] = 1.0f;
            return vector;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN_PATTERN.matcher(lower);
        while (matcher.find()) {
            String run = matcher.group();
            if (containsCjk(run)) {
                addToken(vector, "run:" + run, 0.75f);
                int[] cps = run.codePoints().toArray();
                for (int i = 0; i < cps.length; i++) {
                    addToken(vector, "c:" + new String(Character.toChars(cps[i])), 1.0f);
                    if (i + 1 < cps.length) {
                        addToken(vector, "b:" + new String(Character.toChars(cps[i]))
                                + new String(Character.toChars(cps[i + 1])), 0.5f);
                    }
                }
            } else {
                addToken(vector, "w:" + run, 1.0f);
            }
        }
        return normalize(vector);
    }

    private static void addToken(float[] vector, String token, float weight) {
        int hash = fnv1a(token.getBytes(StandardCharsets.UTF_8));
        int index = (hash & 0x7fffffff) % DIMENSIONS;
        float sign = ((hash >>> 16) & 1) == 0 ? 1.0f : -1.0f;
        vector[index] += sign * weight;
    }

    private static int fnv1a(byte[] bytes) {
        int hash = FNV_OFFSET_BASIS;
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(codePoint);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static float[] normalize(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0.0) {
            vector[0] = 1.0f;
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }
}
