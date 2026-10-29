package com.agentforge.agent.service;

import com.agentforge.agent.domain.RunStatus;
import com.agentforge.agent.entity.AgentDefinitionEntity;
import com.agentforge.agent.entity.AgentMessageEntity;
import com.agentforge.agent.entity.AgentRunEntity;
import com.agentforge.agent.entity.AgentRunStepEntity;
import com.agentforge.agent.entity.AgentSessionEntity;
import com.agentforge.agent.event.AgentEvent;
import com.agentforge.agent.event.AgentEvents;
import com.agentforge.agent.repository.AgentMessageRepository;
import com.agentforge.agent.repository.AgentRunRepository;
import com.agentforge.agent.repository.AgentRunStepRepository;
import com.agentforge.agent.repository.AgentSessionRepository;
import com.agentforge.agent.runtime.AgentEventSink;
import com.agentforge.agent.runtime.QuotaErrors;
import com.agentforge.agent.runtime.ReactAgentFactory;
import com.agentforge.agent.runtime.RunContext;
import com.agentforge.agent.runtime.RunContextHolder;
import com.agentforge.agent.runtime.RunRegistry;
import com.agentforge.audit.quota.QuotaService;
import com.agentforge.audit.usage.UsageContext;
import com.agentforge.common.context.TraceContext;
import com.agentforge.common.context.TenantContext;
import com.agentforge.common.context.UserContext;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.QuotaExceededException;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Agent 运行生命周期：创建会话/运行记录、构建 Agent、执行、落库统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionService {

    private final AgentService agentService;
    private final ReactAgentFactory agentFactory;
    private final AgentRunRepository runRepository;
    private final AgentRunStepRepository stepRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentSessionRepository sessionRepository;
    private final RunRegistry runRegistry;
    private final ObjectProvider<QuotaService> quotaServiceProvider;

    /** 一次运行的只读句柄。 */
    public record Execution(AgentDefinitionEntity definition, Agent agent, RunContext context,
                            RunnableConfig config, AgentRunEntity run, String sessionId, String message,
                            long startedAtNanos) {
    }

    @Transactional
    public Execution start(Long agentId, String message, String sessionId, boolean streaming, AgentEventSink sink) {
        if (message == null || message.isBlank()) {
            throw BizException.badRequest("message 不能为空");
        }
        AgentDefinitionEntity definition = agentService.load(agentId);
        if (Boolean.FALSE.equals(definition.getEnabled())) {
            throw BizException.badRequest("Agent 已禁用: " + agentId);
        }
        Long tenantId = TenantContext.getCurrentTenantId();
        Long userId = UserContext.getUserIdOrNull();
        assertQuota(userId);
        String resolvedSessionId = sessionId == null || sessionId.isBlank()
                ? UUID.randomUUID().toString() : sessionId.trim();

        AgentSessionEntity session = sessionRepository.findBySessionId(resolvedSessionId).orElseGet(() -> {
            AgentSessionEntity created = new AgentSessionEntity();
            created.setTenantId(tenantId);
            created.setAgentId(definition.getId());
            created.setSessionId(resolvedSessionId);
            created.setUserId(userId);
            created.setTitle(message.length() > 60 ? message.substring(0, 60) : message);
            created.setMessageCount(0);
            return sessionRepository.save(created);
        });

        AgentRunEntity run = new AgentRunEntity();
        run.setTenantId(tenantId);
        run.setAgentId(definition.getId());
        run.setAgentVersion(definition.getCurrentVersion());
        run.setSessionId(resolvedSessionId);
        run.setUserId(userId);
        run.setStatus(RunStatus.RUNNING);
        run.setInput(message);
        run.setModelId(definition.getModelId());
        run.setTraceId(TraceContext.currentOrCreate(TraceContext.getTraceId()));
        run.setStartedAt(LocalDateTime.now());
        run.setPromptTokens(0L);
        run.setCompletionTokens(0L);
        run.setTotalTokens(0L);
        run = runRepository.save(run);

        saveMessage(tenantId, definition.getId(), resolvedSessionId, run.getId(), "user", message);

        RunContext context = new RunContext(run.getId(), definition.getId(), resolvedSessionId,
                tenantId, userId, streaming, sink);
        Agent agent = agentFactory.build(definition, context);
        run.setModelName(context.getModelName());
        runRepository.save(run);
        runRegistry.register(context);

        RunnableConfig config = RunnableConfig.builder().threadId(resolvedSessionId).build();
        log.info("启动运行 runId={} agentId={} sessionId={} streaming={} model={} traceId={}",
                run.getId(), agentId, resolvedSessionId, streaming, context.getModelName(), run.getTraceId());
        return new Execution(definition, agent, context, config, run, resolvedSessionId, message,
                System.nanoTime());
    }

    /** 在当前（运行）线程绑定审计上下文。 */
    public void bindRunThread(Execution execution) {
        RunContextHolder.set(execution.context());
        UsageContext.open(execution.context().getTenantId(), execution.context().getUserId(),
                execution.context().getAgentId(), execution.context().getRunId(), execution.sessionId());
    }

    /** 非流式执行：SINGLE 走 ReactAgent.call，编排类型取图输出。 */
    public String execute(Execution execution) {
        Agent agent = execution.agent();
        if (agent instanceof ReactAgent reactAgent) {
            try {
                AssistantMessage answer = reactAgent.call(execution.message(), execution.config());
                assertNoQuotaExceeded(execution.context());
                return answer == null ? "" : answer.getText();
            } catch (Exception e) {
                throw asQuotaOrIllegalState(e);
            }
        }
        NodeOutput output;
        try {
            output = agent.invokeAndGetOutput(execution.message(), execution.config()).orElse(null);
        } catch (Exception e) {
            throw asQuotaOrIllegalState(e);
        }
        assertNoQuotaExceeded(execution.context());
        if (output != null && output.tokenUsage() != null) {
            execution.context().addUsage(output.tokenUsage());
        }
        return extractText(output);
    }

    /**
     * SAA 的 AgentLlmNode 会把模型异常吞成 AssistantMessage，额度异常取不到时以上下文留痕为准。
     */
    private static void assertNoQuotaExceeded(RunContext context) {
        QuotaExceededException quota = context.getQuotaError();
        if (quota != null) {
            throw quota;
        }
    }

    /**
     * 额度异常必须保持 42901 上抛，其余包装为运行失败。
     */
    private static RuntimeException asQuotaOrIllegalState(Exception error) {
        QuotaExceededException quota = QuotaErrors.find(error);
        if (quota != null) {
            return quota;
        }
        return new IllegalStateException("Agent 调用失败: " + error.getMessage(), error);
    }

    /**
     * 事务内预检额度：不足时直接抛 42901 回滚，不产生会话/运行等脏数据。
     */
    private void assertQuota(Long userId) {
        if (userId == null) {
            return;
        }
        QuotaService quotaService = quotaServiceProvider.getIfAvailable();
        if (quotaService != null) {
            quotaService.assertCanConsume(userId);
        }
    }

    /** 从图输出提取最终文本。 */
    public static String extractText(NodeOutput output) {
        if (output == null || output.state() == null) {
            return "";
        }
        Object messages = output.state().value("messages").orElse(null);
        if (messages instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i) instanceof AssistantMessage assistant && assistant.getText() != null) {
                    return assistant.getText();
                }
            }
        }
        Object value = output.state().data().get("output");
        if (value instanceof String text) {
            return text;
        }
        Object merged = output.state().data().get("merged_results");
        return merged == null ? "" : String.valueOf(merged);
    }

    /**
     * 结束运行：保存步骤、运行统计、assistant 消息，并清理线程上下文。
     */
    @Transactional
    public AgentRunEntity complete(Execution execution, String answer, String status, String error) {
        RunContext context = execution.context();
        long elapsedMs = Duration.ofNanos(Math.max(0L, System.nanoTime() - execution.startedAtNanos())).toMillis();
        try {
            AgentRunEntity run = execution.run();
            run.setStatus(status);
            run.setOutput(answer);
            run.setErrorMessage(error == null ? null : truncate(error, 1000));
            run.setPromptTokens(context.usageSnapshot().promptTokens());
            run.setCompletionTokens(context.usageSnapshot().completionTokens());
            run.setTotalTokens(context.usageSnapshot().totalTokens());
            run.setModelName(context.getModelName());
            run.setFinishedAt(LocalDateTime.now());
            runRepository.save(run);
            persistSteps(run.getId(), context.getSteps());
            if (answer != null) {
                saveMessage(run.getTenantId(), run.getAgentId(), run.getSessionId(), run.getId(),
                        "assistant", answer);
                sessionRepository.findBySessionId(run.getSessionId()).ifPresent(session -> {
                    session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 2);
                    sessionRepository.save(session);
                });
            }
            log.info("运行结束 runId={} status={} elapsedMs={} tokens={}", run.getId(), status, elapsedMs,
                    run.getTotalTokens());
            return run;
        } finally {
            runRegistry.unregister(execution.context().getRunId());
            UsageContext.close();
            RunContextHolder.clear();
        }
    }

    public AgentEvents.RunCompleted completedEvent(Execution execution, AgentRunEntity run) {
        long elapsedMs = Duration.ofNanos(Math.max(0L, System.nanoTime() - execution.startedAtNanos())).toMillis();
        return new AgentEvents.RunCompleted(run.getId(), run.getStatus(), elapsedMs,
                execution.context().usageSnapshot());
    }

    @Transactional(readOnly = true)
    public AgentRunEntity loadRun(Long runId) {
        return runRepository.findByIdAndTenantId(runId, TenantContext.getCurrentTenantId())
                .orElseThrow(() -> BizException.notFound("运行记录不存在: " + runId));
    }

    public List<AgentRunStepEntity> stepsOf(Long runId) {
        return stepRepository.findByRunIdOrderBySeqNoAscIdAsc(runId);
    }

    private void persistSteps(Long runId, List<RunContext.StepRecord> steps) {
        stepRepository.deleteByRunId(runId);
        int index = 0;
        for (RunContext.StepRecord step : steps) {
            AgentRunStepEntity entity = new AgentRunStepEntity();
            entity.setRunId(runId);
            entity.setSeqNo(step.seqNo() > 0 ? step.seqNo() : ++index);
            entity.setAgentName(step.agentName());
            entity.setNodeName(step.nodeName());
            entity.setStepType(step.stepType());
            entity.setToolName(step.toolName());
            entity.setInput(truncate(step.input(), 4000));
            entity.setOutput(truncate(step.output(), 4000));
            entity.setStatus(step.status());
            entity.setElapsedMs(step.elapsedMs());
            stepRepository.save(entity);
        }
    }

    private void saveMessage(Long tenantId, Long agentId, String sessionId, Long runId, String role,
                             String content) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setTenantId(tenantId);
        message.setAgentId(agentId);
        message.setSessionId(sessionId);
        message.setRunId(runId);
        message.setRole(role);
        message.setContent(truncate(content, 100_000));
        messageRepository.save(message);
    }

    public void emitError(RunContext context, int code, String message) {
        context.emit(AgentEvent.ERROR, new AgentEvents.ErrorData(code, message));
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
