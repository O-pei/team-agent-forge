package com.agentforge.audit.gateway.mock;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 零依赖 Mock 模型：无 API Key 时保证全链路（对话、Token 审计、SSE）可跑。
 *
 * <p>返回可预测文本并模拟 usage；用于开发、演示与集成测试。</p>
 */
public class MockChatModel implements ChatModel {

    private final String modelName;

    public MockChatModel() {
        this("mock-chat");
    }

    public MockChatModel(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String userText = lastUserText(prompt);
        String content = "（Mock 模型）已收到你的消息：" + userText
                + "\n\n当前未配置真实模型，请在「模型配置」中添加 DashScope/OpenAI 兼容模型，或设置环境变量 AI_DASHSCOPE_API_KEY。";
        return response(content, prompt.getInstructions());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String userText = lastUserText(prompt);
        String content = "（Mock 模型）已收到你的消息：" + userText + "。这是模拟的流式输出。";
        List<ChatResponse> chunks = new ArrayList<>();
        int step = Math.max(4, content.length() / 8);
        for (int i = 0; i < content.length(); i += step) {
            chunks.add(response(content.substring(i, Math.min(content.length(), i + step)),
                    prompt.getInstructions()));
        }
        return Flux.fromIterable(chunks);
    }

    private ChatResponse response(String content, List<Message> instructions) {
        int promptTokens = estimateTokens(instructions);
        int completionTokens = Math.max(1, estimateTokens(content));
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id(UUID.randomUUID().toString())
                .model(modelName)
                .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))), metadata);
    }

    private static String lastUserText(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message message = instructions.get(i);
            if (message.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) {
                return message.getText();
            }
        }
        return instructions.isEmpty() ? "" : instructions.get(instructions.size() - 1).getText();
    }

    private static int estimateTokens(List<Message> instructions) {
        int chars = 0;
        for (Message message : instructions) {
            chars += message.getText() == null ? 0 : message.getText().length();
        }
        return Math.max(1, chars / 4);
    }

    private static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }
}
