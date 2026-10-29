package com.agentforge.rag.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切分器：默认 512 字符/块、64 字符重叠，优先在段落边界切分，长段落硬切并保留重叠。
 */
public final class TextChunker {

    public static final int DEFAULT_CHUNK_SIZE = 512;
    public static final int DEFAULT_OVERLAP = 64;

    private TextChunker() {
    }

    public static List<String> split(String text) {
        return split(text, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    /**
     * @param text      原始文本
     * @param chunkSize 目标块大小（字符数），必须 > overlap
     * @param overlap   相邻块重叠字符数
     * @return 非空块列表（保持原文顺序）
     */
    public static List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("chunkSize 必须 > overlap >= 0");
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : splitParagraphs(normalized)) {
            if (paragraph.isBlank()) {
                continue;
            }
            if (paragraph.length() > chunkSize) {
                if (current.length() > 0) {
                    chunks.add(current.toString().strip());
                    current.setLength(0);
                }
                hardSplit(chunks, paragraph, chunkSize, overlap);
                continue;
            }
            if (current.length() > 0 && current.length() + 1 + paragraph.length() > chunkSize) {
                String previous = current.toString().strip();
                chunks.add(previous);
                current.setLength(0);
                String tail = tail(previous, overlap);
                if (!tail.isBlank()) {
                    current.append(tail).append('\n');
                }
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(paragraph);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().strip());
        }
        return chunks.stream().filter(s -> !s.isBlank()).toList();
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    private static String[] splitParagraphs(String text) {
        return text.split("\n\\s*\n");
    }

    private static void hardSplit(List<String> chunks, String paragraph, int chunkSize, int overlap) {
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(start + chunkSize, paragraph.length());
            String part = paragraph.substring(start, end).strip();
            if (!part.isBlank()) {
                chunks.add(part);
            }
            if (end >= paragraph.length()) {
                break;
            }
            start = end - overlap;
        }
    }

    /** 取末尾 overlap 个字符，尽量从空白处开始，避免把单词/汉字硬切一半。 */
    private static String tail(String text, int overlap) {
        if (overlap <= 0 || text.isEmpty()) {
            return "";
        }
        int start = Math.max(0, text.length() - overlap);
        int space = text.indexOf(' ', start);
        int newline = text.indexOf('\n', start);
        int cut = -1;
        if (space >= 0) {
            cut = space;
        }
        if (newline >= 0 && (cut < 0 || newline < cut)) {
            cut = newline;
        }
        if (cut >= 0 && cut < text.length() - 1) {
            return text.substring(cut + 1).strip();
        }
        return text.substring(start).strip();
    }
}
