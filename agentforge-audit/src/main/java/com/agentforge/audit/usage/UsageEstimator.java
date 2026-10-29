package com.agentforge.audit.usage;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Token 估算：服务端未返回 usage 时的兜底策略（与 MockChatModel 一致：约 4 字符 = 1 token）。
 */
public final class UsageEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    private UsageEstimator() {
    }

    /** 估算一段文本的 token 数；null/空文本返回 0。 */
    public static long estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        return Math.max(1L, text.length() / CHARS_PER_TOKEN);
    }

    /** 估算整段 prompt（所有消息文本合计）的 token 数；空 prompt 返回 0。 */
    public static long estimatePrompt(Prompt prompt) {
        if (prompt == null) {
            return 0L;
        }
        long chars = 0;
        for (Message message : prompt.getInstructions()) {
            String text = message.getText();
            if (text != null) {
                chars += text.length();
            }
        }
        return chars == 0 ? 0L : Math.max(1L, chars / CHARS_PER_TOKEN);
    }
}
