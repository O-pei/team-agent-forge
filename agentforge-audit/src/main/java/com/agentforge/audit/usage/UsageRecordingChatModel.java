package com.agentforge.audit.usage;

import com.agentforge.common.context.TenantContext;
import com.agentforge.common.context.TraceContext;
import com.agentforge.common.context.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatModel 装饰器：全量采集 Token usage 并异步上报 TokenUsageService。
 * 非流式取响应 metadata 中的 usage，缺失时按字符数估算；
 * 流式累加输出文本并取最后一个带有效 usage 的 chunk（Spring AI 流式只有最后一块带 usage），缺失时按累加文本估算；
 * 采集失败只记日志，不影响主调用链路。
 */
public class UsageRecordingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordingChatModel.class);

    private final ChatModel delegate;
    private final Long modelId;
    private final String modelName;
    private final String provider;
    private final TokenUsageService tokenUsageService;

    public UsageRecordingChatModel(ChatModel delegate, Long modelId, String modelName, String provider,
                                   TokenUsageService tokenUsageService) {
        this.delegate = delegate;
        this.modelId = modelId;
        this.modelName = modelName;
        this.provider = provider;
        this.tokenUsageService = tokenUsageService;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.nanoTime();
        ChatResponse response = delegate.call(prompt);
        try {
            record(prompt, textOf(response), usageOf(response), false, true, start);
        } catch (Exception ex) {
            log.warn("Token 用量采集失败（call）: {}", ex.getMessage());
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.nanoTime();
        StringBuilder content = new StringBuilder();
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AtomicBoolean recorded = new AtomicBoolean(false);
        return delegate.stream(prompt)
                .doOnNext(chunk -> {
                    String text = textOf(chunk);
                    if (text != null) {
                        content.append(text);
                    }
                    Usage usage = usageOf(chunk);
                    if (hasUsage(usage)) {
                        lastUsage.set(usage);
                    }
                })
                .doOnError(ex -> {
                    if (recorded.compareAndSet(false, true)) {
                        safeRecord(prompt, content.toString(), lastUsage.get(), true, false, start);
                    }
                })
                .doOnComplete(() -> {
                    if (recorded.compareAndSet(false, true)) {
                        safeRecord(prompt, content.toString(), lastUsage.get(), true, true, start);
                    }
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private void safeRecord(Prompt prompt, String completion, Usage usage, boolean stream, boolean success,
                            long startNanos) {
        try {
            record(prompt, completion, usage, stream, success, startNanos);
        } catch (Exception ex) {
            log.warn("Token 用量采集失败（stream={}）: {}", stream, ex.getMessage());
        }
    }

    private void record(Prompt prompt, String completion, Usage usage, boolean stream, boolean success,
                        long startNanos) {
        long latencyMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        long promptTokens;
        long completionTokens;
        long totalTokens;
        if (hasUsage(usage)) {
            promptTokens = tokens(usage.getPromptTokens());
            completionTokens = tokens(usage.getCompletionTokens());
            totalTokens = tokens(usage.getTotalTokens());
            if (totalTokens <= 0) {
                totalTokens = promptTokens + completionTokens;
            }
        } else {
            promptTokens = UsageEstimator.estimatePrompt(prompt);
            completionTokens = UsageEstimator.estimateText(completion);
            totalTokens = promptTokens + completionTokens;
        }
        UsageContext.Context context = UsageContext.current();
        Long tenantId = context != null && context.tenantId() != null
                ? context.tenantId() : TenantContext.getCurrentTenantId();
        Long userId = context != null && context.userId() != null
                ? context.userId() : UserContext.getUserIdOrNull();
        TokenUsageEvent event = new TokenUsageEvent(
                tenantId,
                userId,
                context == null ? null : context.agentId(),
                context == null ? null : context.runId(),
                context == null ? null : context.sessionId(),
                modelId,
                modelName,
                provider,
                promptTokens,
                completionTokens,
                totalTokens,
                stream,
                success,
                latencyMs,
                MDC.get(TraceContext.TRACE_MDC_KEY));
        tokenUsageService.record(event);
    }

    private static boolean hasUsage(Usage usage) {
        return usage != null
                && (tokens(usage.getPromptTokens()) > 0
                || tokens(usage.getCompletionTokens()) > 0
                || tokens(usage.getTotalTokens()) > 0);
    }

    private static long tokens(Integer value) {
        return value == null ? 0L : Math.max(0, value);
    }

    private static String textOf(ChatResponse response) {
        if (response == null) {
            return null;
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            return null;
        }
        return generation.getOutput().getText();
    }

    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }
}
