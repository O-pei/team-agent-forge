package com.agentforge.agent.runtime;

import com.agentforge.audit.quota.QuotaService;
import com.agentforge.common.exception.QuotaExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 额度网关：每次模型调用前复检，超额抛 42901 且不再打到真实模型。
 */
class QuotaGuardChatModelTest {

    private final QuotaService quota = com.agentforge.agent.support.QuotaServiceStubs.allowCalls(2);
    private final StubChatModel delegate = new StubChatModel();
    private final ChatModel guarded = new QuotaGuardChatModel(delegate, 7L, quota);

    @Test
    void allowsCallsWithinQuota() {
        assertThat(guarded.call(new Prompt("你好")).getResult().getOutput().getText()).isEqualTo("ok");
        assertThat(quota.status(7L).dailyUsed()).isEqualTo(1);
        assertThat(delegate.calls.get()).isEqualTo(1);
    }

    @Test
    void blocksCallOnceQuotaExhausted() {
        guarded.call(new Prompt("第一次"));
        guarded.call(new Prompt("第二次"));

        assertThatThrownBy(() -> guarded.call(new Prompt("第三次")))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(error -> assertThat(((QuotaExceededException) error).getCode()).isEqualTo(42901));
        assertThat(delegate.calls.get()).isEqualTo(2);
    }

    @Test
    void blocksStreamSubscriptionOnceQuotaExhausted() {
        guarded.call(new Prompt("第一次"));
        guarded.call(new Prompt("第二次"));

        assertThatThrownBy(() -> guarded.stream(new Prompt("第三次")).blockLast())
                .isInstanceOf(QuotaExceededException.class);
        assertThat(delegate.streamCalls.get()).isZero();
    }

    @Test
    void skipsQuotaWhenServiceOrUserMissing() {
        assertThat(QuotaGuardChatModel.wrapIfNecessary(delegate, null, quota)).isSameAs(delegate);
        assertThat(QuotaGuardChatModel.wrapIfNecessary(delegate, 7L, null)).isSameAs(delegate);
        assertThat(delegate.calls.get()).isZero();
    }

    @Test
    void delegatesDefaultOptions() {
        assertThat(guarded.getDefaultOptions()).isNull();
    }

    @Test
    void reportsQuotaErrorToCallback() {
        java.util.List<QuotaExceededException> reported = new java.util.ArrayList<>();
        ChatModel model = new QuotaGuardChatModel(delegate, 7L,
                com.agentforge.agent.support.QuotaServiceStubs.alwaysExceeded(), reported::add);

        assertThatThrownBy(() -> model.call(new Prompt("你好")))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(reported).hasSize(1);
        assertThat(reported.get(0).getCode()).isEqualTo(42901);
    }

    private static final class StubChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger streamCalls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCalls.incrementAndGet();
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }
}
