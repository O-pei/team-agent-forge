package com.agentforge.agent.runtime;

import com.agentforge.audit.quota.QuotaService;
import com.agentforge.common.exception.QuotaExceededException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

/**
 * 模型调用前的额度网关：每次 call/stream 都复检用户 Token 额度，
 * 超额时抛 QuotaExceededException（42901），让 ReactAgent 的工具循环在
 * 下一次模型调用前终止，避免继续消耗额度。同一用户并发运行多个 Agent 时，
 * 预检与复检之间可能出现少量超额，属于可接受误差。
 */
public class QuotaGuardChatModel implements ChatModel {

    private final ChatModel delegate;
    private final Long ownerUserId;
    private final QuotaService quotaService;
    private final Consumer<QuotaExceededException> onExceeded;

    public QuotaGuardChatModel(ChatModel delegate, Long ownerUserId, QuotaService quotaService) {
        this(delegate, ownerUserId, quotaService, null);
    }

    public QuotaGuardChatModel(ChatModel delegate, Long ownerUserId, QuotaService quotaService,
                               Consumer<QuotaExceededException> onExceeded) {
        this.delegate = delegate;
        this.ownerUserId = ownerUserId;
        this.quotaService = quotaService;
        this.onExceeded = onExceeded;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        assertQuota();
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            assertQuota();
            return delegate.stream(prompt);
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    void assertQuota() {
        if (quotaService == null || ownerUserId == null) {
            return;
        }
        try {
            quotaService.assertCanConsume(ownerUserId);
        } catch (QuotaExceededException e) {
            if (onExceeded != null) {
                onExceeded.accept(e);
            }
            throw e;
        }
    }

    /**
     * 额度服务或用户缺失时不包装，保证无额度实现的部署仍可运行。
     */
    public static ChatModel wrapIfNecessary(ChatModel model, Long userId, QuotaService quotaService) {
        return wrapIfNecessary(model, userId, quotaService, null);
    }

    public static ChatModel wrapIfNecessary(ChatModel model, Long userId, QuotaService quotaService,
                                            Consumer<QuotaExceededException> onExceeded) {
        if (model == null || quotaService == null || userId == null || model instanceof QuotaGuardChatModel) {
            return model;
        }
        return new QuotaGuardChatModel(model, userId, quotaService, onExceeded);
    }

    @Override
    public String toString() {
        return "QuotaGuardChatModel[" + delegate + "]";
    }
}
