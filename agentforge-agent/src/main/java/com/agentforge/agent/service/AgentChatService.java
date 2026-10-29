package com.agentforge.agent.service;

import com.agentforge.agent.dto.ChatStreamRequest;
import com.agentforge.agent.event.AgentEvent;
import com.agentforge.agent.event.AgentEvents;
import com.agentforge.agent.runtime.AgentEventSink;
import com.agentforge.agent.runtime.QuotaErrors;
import com.agentforge.agent.runtime.RunContext;
import com.agentforge.common.api.ErrorCode;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.QuotaExceededException;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE 对话：异步执行 Agent，按契约推送 delta / tool / usage / done 等事件。
 */
@Slf4j
@Service
public class AgentChatService {

    private static final long SSE_TIMEOUT_MS = 600_000L;
    private static final ScheduledExecutorService PING_SCHEDULER = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "agent-sse-ping");
        thread.setDaemon(true);
        return thread;
    });

    private final AgentExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;

    public AgentChatService(AgentExecutionService executionService, ObjectMapper objectMapper,
                            @Qualifier("agentRunExecutor") ThreadPoolTaskExecutor executor) {
        this.executionService = executionService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public SseEmitter chat(ChatStreamRequest request) {
        if (request == null || request.agentId() == null) {
            throw BizException.badRequest("agentId 不能为空");
        }
        if (request.message() == null || request.message().isBlank()) {
            throw BizException.badRequest("message 不能为空");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AgentEventSink sink = (event, data) -> send(emitter, event, data);
        AgentExecutionService.Execution execution;
        try {
            execution = executionService.start(
                    request.agentId(), request.message(), request.sessionId(), true, sink);
        } catch (RuntimeException e) {
            // 启动阶段（校验/落库/构建 Agent）失败也走 SSE error 事件，避免客户端拿到 JSON 500
            log.warn("SSE 启动失败 agentId={}", request.agentId(), e);
            send(emitter, AgentEvent.ERROR, new AgentEvents.ErrorData(errorCode(e), errorMessage(e)));
            emitter.complete();
            return emitter;
        }

        ScheduledFuture<?> ping = PING_SCHEDULER.scheduleAtFixedRate(
                () -> send(emitter, AgentEvent.PING, new AgentEvents.MessageDelta("")),
                15, 15, TimeUnit.SECONDS);
        Runnable stopPing = () -> ping.cancel(false);
        emitter.onCompletion(stopPing);
        emitter.onTimeout(() -> {
            stopPing.run();
            execution.context().cancel();
        });
        emitter.onError(error -> stopPing.run());

        executor.execute(() -> runStream(execution, emitter));
        return emitter;
    }

    private void runStream(AgentExecutionService.Execution execution, SseEmitter emitter) {
        RunContext context = execution.context();
        StringBuilder answer = new StringBuilder();
        StringBuilder fallback = new StringBuilder();
        try {
            executionService.bindRunThread(execution);
            context.emit(AgentEvent.RUN_STARTED, new AgentEvents.RunStarted(
                    context.getRunId(), context.getAgentId(), context.getSessionId(), context.getModelName()));
            if (execution.agent() instanceof ReactAgent reactAgent) {
                reactAgent.stream(execution.message(), execution.config())
                        .takeWhile(node -> !context.isCancelled())
                        .doOnNext(node -> handleNode(node, context, answer, fallback))
                        .blockLast();
            } else {
                String text = executionService.execute(execution);
                if (text != null && !text.isEmpty()) {
                    context.emit(AgentEvent.MESSAGE_DELTA, new AgentEvents.MessageDelta(text));
                    answer.append(text);
                }
            }
            if (answer.length() == 0 && fallback.length() > 0) {
                context.emit(AgentEvent.MESSAGE_DELTA, new AgentEvents.MessageDelta(fallback.toString()));
                answer.append(fallback);
            }
            // SAA 的 AgentLlmNode 会把模型异常吞成助手消息，额度错误需在流结束后主动判定
            if (context.getQuotaError() != null) {
                throw context.getQuotaError();
            }
            String finalAnswer = answer.toString();
            context.resolveStreamOutput(finalAnswer);
            context.emit(AgentEvent.MESSAGE_COMPLETED, new AgentEvents.MessageCompleted(finalAnswer));
            context.emit(AgentEvent.USAGE, context.usageSnapshot());
            boolean cancelled = context.isCancelled();
            var run = executionService.complete(execution, finalAnswer,
                    cancelled ? com.agentforge.agent.domain.RunStatus.CANCELLED
                            : com.agentforge.agent.domain.RunStatus.SUCCEEDED, null);
            context.emit(AgentEvent.RUN_COMPLETED, executionService.completedEvent(execution, run));
            emitter.complete();
        } catch (Exception e) {
            handleRunFailure(execution, answer, e, emitter);
        }
    }

    /**
     * 运行失败：先发 error（额度不足为 42901），再落 FAILED 运行记录与 run.completed。
     */
    void handleRunFailure(AgentExecutionService.Execution execution, StringBuilder answer, Exception error,
                          SseEmitter emitter) {
        RunContext context = execution.context();
        log.warn("SSE 运行失败 runId={}", context.getRunId(), error);
        try {
            context.emit(AgentEvent.ERROR, new AgentEvents.ErrorData(errorCode(error), errorMessage(error)));
            var run = executionService.complete(execution,
                    answer.length() == 0 ? null : answer.toString(),
                    com.agentforge.agent.domain.RunStatus.FAILED, errorMessage(error));
            context.emit(AgentEvent.RUN_COMPLETED, executionService.completedEvent(execution, run));
        } catch (Exception persistenceError) {
            log.error("保存失败运行记录出错 runId={}", context.getRunId(), persistenceError);
        }
        emitter.complete();
    }

    static int errorCode(Throwable error) {
        QuotaExceededException quota = QuotaErrors.find(error);
        if (quota != null) {
            return quota.getCode();
        }
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof BizException biz) {
                return biz.getCode();
            }
            cursor = cursor.getCause();
        }
        return ErrorCode.INTERNAL_ERROR.getCode();
    }

    static String errorMessage(Throwable error) {
        QuotaExceededException quota = QuotaErrors.find(error);
        if (quota != null && quota.getMessage() != null) {
            return quota.getMessage();
        }
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof BizException biz && biz.getMessage() != null) {
                return biz.getMessage();
            }
            cursor = cursor.getCause();
        }
        return error.getMessage();
    }

    private void handleNode(NodeOutput node, RunContext context, StringBuilder answer, StringBuilder fallback) {
        if (node == null) {
            return;
        }
        if (node.tokenUsage() != null) {
            context.addUsage(node.tokenUsage());
        }
        if (node instanceof StreamingOutput<?> streaming) {
            if (streaming.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
                String chunk = streaming.chunk();
                if (chunk != null && !chunk.isEmpty()) {
                    context.emit(AgentEvent.MESSAGE_DELTA, new AgentEvents.MessageDelta(chunk));
                    answer.append(chunk);
                }
            }
        }
        String text = AgentExecutionService.extractText(node);
        if (text != null && !text.isEmpty()) {
            fallback.setLength(0);
            fallback.append(text);
        }
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            String json = toJson(event, data);
            emitter.send(SseEmitter.event().name(event).data(json));
        } catch (Exception e) {
            throw new IllegalStateException("SSE 发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 序列化事件负载：补上与 event 名一致的 type 字段，便于前端统一按 type/content 解析，
     * 同时保留契约字段。
     */
    String toJson(String event, Object data) {
        try {
            JsonNode node = objectMapper.valueToTree(data);
            if (node instanceof ObjectNode objectNode) {
                objectNode.put("type", event);
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("SSE 序列化失败: " + e.getMessage(), e);
        }
    }
}
