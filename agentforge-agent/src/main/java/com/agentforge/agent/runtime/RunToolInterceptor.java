package com.agentforge.agent.runtime;

import com.agentforge.agent.event.AgentEvent;
import com.agentforge.agent.event.AgentEvents;
import com.agentforge.agent.tool.AgentToolRegistry;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具调用拦截器：推送 tool.call / tool.result 事件并记录步骤。
 */
@Slf4j
@RequiredArgsConstructor
public class RunToolInterceptor extends ToolInterceptor {

    private final RunContext context;
    private final AgentToolRegistry toolRegistry;

    @Override
    public String getName() {
        return "run-tool-interceptor";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();
        String arguments = truncate(request.getArguments(), 2000);
        context.emit(AgentEvent.TOOL_CALL,
                new AgentEvents.ToolCall(toolName, arguments, toolRegistry.riskLevel(toolName)));
        int seq = context.addStep("TOOL", toolName, arguments);
        long start = System.currentTimeMillis();
        try {
            ToolCallResponse response = handler.call(request);
            long elapsed = System.currentTimeMillis() - start;
            boolean success = response != null && !response.isError();
            String result = response == null ? "" : response.getResult();
            context.emit(AgentEvent.TOOL_RESULT,
                    new AgentEvents.ToolResult(toolName, truncate(result, 500), success, elapsed));
            context.finishStep(seq, success ? "SUCCEEDED" : "FAILED", truncate(result, 4000), elapsed);
            return response;
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("工具调用失败 runId={} tool={}: {}", context.getRunId(), toolName, e.getMessage());
            context.emit(AgentEvent.TOOL_RESULT,
                    new AgentEvents.ToolResult(toolName, truncate(e.getMessage(), 500), false, elapsed));
            context.finishStep(seq, "FAILED", truncate(e.getMessage(), 4000), elapsed);
            throw e;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
