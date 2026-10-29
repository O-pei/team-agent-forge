package com.agentforge.agent.runtime;

import com.agentforge.agent.event.AgentEvent;
import com.agentforge.agent.event.AgentEvents;
import com.agentforge.common.exception.QuotaExceededException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单次运行的可变上下文：事件推送、token 累计、步骤记录、取消标记。
 */
@Slf4j
public class RunContext {

    @Getter
    private final Long runId;
    @Getter
    private final Long agentId;
    @Getter
    private final String sessionId;
    @Getter
    private final Long tenantId;
    @Getter
    private final Long userId;
    @Getter
    private final boolean streaming;
    private final AgentEventSink sink;
    private final AtomicReference<String> modelName = new AtomicReference<>("mock");
    private final LongAdder promptTokens = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();
    private final LongAdder totalTokens = new LongAdder();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<QuotaExceededException> quotaError = new AtomicReference<>();
    private final AtomicInteger seq = new AtomicInteger(0);
    private final AtomicInteger citationIndex = new AtomicInteger(0);
    @Getter
    private final List<StepRecord> steps = new CopyOnWriteArrayList<>();

    public RunContext(Long runId, Long agentId, String sessionId, Long tenantId, Long userId,
                      boolean streaming, AgentEventSink sink) {
        this.runId = runId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.streaming = streaming;
        this.sink = sink;
    }

    public String getModelName() {
        return modelName.get();
    }

    public void setModelName(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            this.modelName.set(modelName);
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
    }

    /**
     * 记录额度异常并停止运行。SAA 的 AgentLlmNode 会把模型异常吞成一条 AssistantMessage，
     * 额度错误必须留痕，由运行层在流结束后判定失败并返回 42901。
     */
    public void setQuotaError(QuotaExceededException error) {
        if (error != null && quotaError.compareAndSet(null, error)) {
            cancelled.set(true);
        }
    }

    public QuotaExceededException getQuotaError() {
        return quotaError.get();
    }

    /** 推送 SSE 事件；sink 异常时标记取消，避免线程持续写已关闭的连接。 */
    public void emit(String event, Object data) {
        if (sink == null) {
            return;
        }
        try {
            sink.send(event, data);
        } catch (Exception e) {
            log.debug("SSE 推送失败，终止运行 runId={}: {}", runId, e.getMessage());
            cancelled.set(true);
        }
    }

    public void addUsage(Usage usage) {
        if (usage == null) {
            return;
        }
        int prompt = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        int total = usage.getTotalTokens() == null ? prompt + completion : usage.getTotalTokens();
        promptTokens.add(prompt);
        completionTokens.add(completion);
        totalTokens.add(total);
    }

    public AgentEvents.Usage usageSnapshot() {
        return new AgentEvents.Usage(getModelName(), promptTokens.sum(), completionTokens.sum(),
                totalTokens.sum(), 0.0d);
    }

    public int addStep(String stepType, String toolName, String input) {
        int no = seq.incrementAndGet();
        steps.add(new StepRecord(no, null, null, stepType, toolName, input, null, "RUNNING", 0L));
        return no;
    }

    public void finishStep(int seqNo, String status, String output, long elapsedMs) {
        for (int i = 0; i < steps.size(); i++) {
            StepRecord record = steps.get(i);
            if (record.seqNo() == seqNo) {
                steps.set(i, new StepRecord(record.seqNo(), record.agentName(), record.nodeName(),
                        record.stepType(), record.toolName(), record.input(), output, status, elapsedMs));
                return;
            }
        }
    }

    /**
     * 流式调用结束后回填 MODEL 步骤的真实输出：拦截器在拦截阶段只能拿到 Flux，
     * 这里把最后一个占位步骤的 output 替换为最终文本。
     */
    public void resolveStreamOutput(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            StepRecord record = steps.get(i);
            if ("MODEL".equals(record.stepType()) && RunModelInterceptor.STREAM_PLACEHOLDER.equals(record.output())) {
                steps.set(i, new StepRecord(record.seqNo(), record.agentName(), record.nodeName(),
                        record.stepType(), record.toolName(), record.input(), text, record.status(), record.elapsedMs()));
                return;
            }
        }
    }

    public int nextCitationIndex() {
        return citationIndex.incrementAndGet();
    }

    /** 运行步骤快照。 */
    public record StepRecord(
            int seqNo,
            String agentName,
            String nodeName,
            String stepType,
            String toolName,
            String input,
            String output,
            String status,
            long elapsedMs) {
    }
}
