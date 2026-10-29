package com.agentforge.agent.service;

import com.agentforge.agent.dto.ChatStreamRequest;
import com.agentforge.agent.event.AgentEvent;
import com.agentforge.agent.event.AgentEvents;
import com.agentforge.agent.runtime.AgentEventSink;
import com.agentforge.agent.runtime.RunContext;
import com.agentforge.common.exception.BizException;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.Agent;
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
 * SSE 瀵硅瘽锛氬紓姝ユ墽琛?Agent锛屾寜濂戠害鎺ㄩ€?delta / tool / usage / done 绛変簨浠躲€? */
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
            throw BizException.badRequest("agentId 涓嶈兘涓虹┖");
        }
        if (request.message() == null || request.message().isBlank()) {
            throw BizException.badRequest("message 涓嶈兘涓虹┖");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AgentEventSink sink = (event, data) -> send(emitter, event, data);
        AgentExecutionService.Execution execution = executionService.start(
                request.agentId(), request.message(), request.sessionId(), true, sink);

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
            Agent agent = execution.agent();
            if (agent instanceof ReactAgent reactAgent) {
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
            String finalAnswer = answer.toString();
            context.emit(AgentEvent.MESSAGE_COMPLETED, new AgentEvents.MessageCompleted(finalAnswer));
            context.emit(AgentEvent.USAGE, context.usageSnapshot());
            boolean cancelled = context.isCancelled();
            var run = executionService.complete(execution, finalAnswer,
                    cancelled ? com.agentforge.agent.domain.RunStatus.CANCELLED
                            : com.agentforge.agent.domain.RunStatus.SUCCEEDED, null);
            context.emit(AgentEvent.RUN_COMPLETED, executionService.completedEvent(execution, run));
            emitter.complete();
        } catch (Exception e) {
            log.error("SSE 杩愯澶辫触 runId={}", context.getRunId(), e);
            try {
                context.emit(AgentEvent.ERROR, new AgentEvents.ErrorData(
                        com.agentforge.common.api.ErrorCode.INTERNAL_ERROR.getCode(), e.getMessage()));
                var run = executionService.complete(execution,
                        answer.length() == 0 ? null : answer.toString(),
                        com.agentforge.agent.domain.RunStatus.FAILED, e.getMessage());
                context.emit(AgentEvent.RUN_COMPLETED, executionService.completedEvent(execution, run));
            } catch (Exception persistenceError) {
                log.error("淇濆瓨澶辫触杩愯璁板綍鍑洪敊 runId={}", context.getRunId(), persistenceError);
            }
            emitter.complete();
        }
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
            throw new IllegalStateException("SSE 鍙戦€佸け璐? " + e.getMessage(), e);
        }
    }

    /**
     * 浜嬩欢 data 搴忓垪鍖栵細缁熶竴闄勫甫 {@code type} 瀛楁锛堜笌 event 鍚嶄竴鑷达級锛屼究浜庡墠绔寜
     * {@code {type, content}} 缁熶竴瑙ｆ瀽锛涘悓鏃朵繚鐣欏绾﹀瓧娈碉紙濡?message.delta 鐨?content锛夈€?     */
    String toJson(String event, Object data) {
        try {
            JsonNode node = objectMapper.valueToTree(data);
            if (node instanceof ObjectNode objectNode) {
                objectNode.put("type", event);
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("SSE 搴忓垪鍖栧け璐? " + e.getMessage(), e);
        }
    }
}
