package com.agentforge.audit.usage;

import com.agentforge.audit.gateway.mock.MockChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UsageRecordingChatModel 采集语义测试：流式累加、缺失 usage 估算、上下文透传。
 */
class UsageRecordingChatModelTest {

    private final List<TokenUsageEvent> events = new ArrayList<>();
    private final TokenUsageService capturingService = events::add;

    @Test
    void streamingAccumulatesTextAndTakesLastValidUsage() {
        ChatModel delegate = new StreamingChatModel(Flux.just(
                chunkWithUsage("Hello ", 3, 1, 4),
                chunkWithUsage("world", 3, 2, 5)));

        UsageRecordingChatModel model =
                new UsageRecordingChatModel(delegate, 7L, "demo-model", "MOCK", capturingService);
        List<ChatResponse> chunks = model.stream(new Prompt(List.of(new UserMessage("hi"))))
                .collectList()
                .block();

        assertEquals(2, chunks.size());
        assertEquals(1, events.size());
        TokenUsageEvent event = events.get(0);
        assertTrue(event.stream());
        assertTrue(event.success());
        assertEquals(7L, event.modelId());
        assertEquals("demo-model", event.modelName());
        assertEquals(3L, event.promptTokens());
        assertEquals(2L, event.completionTokens());
        assertEquals(5L, event.totalTokens());
    }

    @Test
    void streamingWithoutUsageEstimatesFromAccumulatedChars() {
        ChatModel delegate = new StreamingChatModel(Flux.just(noUsageChunk("Hello "), noUsageChunk("world")));

        UsageRecordingChatModel model =
                new UsageRecordingChatModel(delegate, 1L, "m", "MOCK", capturingService);
        model.stream(new Prompt(List.of(new UserMessage("hi")))).collectList().block();

        TokenUsageEvent event = events.get(0);
        // prompt "hi" 2 字符 -> 1 token；累积回复 "Hello world" 11 字符 -> 2 token
        assertEquals(1L, event.promptTokens());
        assertEquals(2L, event.completionTokens());
        assertEquals(3L, event.totalTokens());
    }

    @Test
    void callRecordsUsageAndUsageContextIsPassedThrough() {
        UsageRecordingChatModel model = new UsageRecordingChatModel(
                new MockChatModel("mock-chat"), 0L, "mock-chat", "MOCK", capturingService);

        UsageContext.open(9L, 88L, 5L, 6L, "session-1");
        try {
            ChatResponse response = model.call(new Prompt(List.of(new UserMessage("你好，请介绍一下你自己"))));
            assertFalse(response.getResult().getOutput().getText().isBlank());
        } finally {
            UsageContext.close();
        }

        assertEquals(1, events.size());
        TokenUsageEvent event = events.get(0);
        assertFalse(event.stream());
        assertTrue(event.success());
        assertEquals(9L, event.tenantId());
        assertEquals(88L, event.userId());
        assertEquals(5L, event.agentId());
        assertEquals(6L, event.runId());
        assertEquals("session-1", event.sessionId());
        assertTrue(event.totalTokens() > 0);
        assertTrue(event.completionTokens() > 0);
    }

    private static ChatResponse chunkWithUsage(String text, int promptTokens, int completionTokens, int totalTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens, totalTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    private static ChatResponse noUsageChunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static final class StreamingChatModel implements ChatModel {

        private final Flux<ChatResponse> chunks;

        private StreamingChatModel(Flux<ChatResponse> chunks) {
            this.chunks = chunks;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return chunks;
        }
    }
}
