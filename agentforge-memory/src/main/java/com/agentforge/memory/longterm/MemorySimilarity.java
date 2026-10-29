package com.agentforge.memory.longterm;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 词面相似度：中文按字符 + bigram，英文按单词，计算 Jaccard 相似度。
 *
 * <p>不依赖向量库，保证无 Redis/无 Embedding 的零配置环境也能按关键词召回长期记忆。</p>
 */
public final class MemorySimilarity {

    private MemorySimilarity() {
    }

    public static double similarity(String query, String fact) {
        Set<String> queryTokens = tokenize(query);
        Set<String> factTokens = tokenize(fact);
        if (queryTokens.isEmpty() || factTokens.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (String token : queryTokens) {
            if (factTokens.contains(token)) {
                intersection++;
            }
        }
        int union = queryTokens.size() + factTokens.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new HashSet<>();
        StringBuilder word = new StringBuilder();
        Character previousCjk = null;
        for (char ch : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (isAsciiAlphanumeric(ch)) {
                word.append(ch);
                previousCjk = null;
            } else if (isCjk(ch)) {
                flush(word, tokens);
                tokens.add(String.valueOf(ch));
                if (previousCjk != null) {
                    tokens.add(previousCjk + String.valueOf(ch));
                }
                previousCjk = ch;
            } else {
                flush(word, tokens);
                previousCjk = null;
            }
        }
        flush(word, tokens);
        return tokens;
    }

    private static void flush(StringBuilder word, Set<String> tokens) {
        if (!word.isEmpty()) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private static boolean isAsciiAlphanumeric(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
    }

    private static boolean isCjk(char ch) {
        return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
    }
}
