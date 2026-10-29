package com.agentforge.agent.runtime;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 模型调用拦截器：累计 Token、记录模型步骤。
 */
@Slf4j
@RequiredArgsConstructor
public class RunModelInterceptor extends ModelInterceptor {

    private final RunContext context;

    @Override
    public String getName() {
        return "run-model-interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String input = summarizeRequest(request);
        int seq = context.addStep("MODEL", null, input);
        long start = System.currentTimeMillis();
        try {
            ModelResponse response = handler.call(request);
            ChatResponse chatResponse = response == null ? null : response.getChatResponse();
            if (chatResponse != null && chatResponse.getMetadata() != null) {
                Usage usage = chatResponse.getMetadata().getUsage();
                if (usage != null) {
                    context.addUsage(usage);
                }
            }
            String output = summarizeResponse(response);
            context.finishStep(seq, "SUCCEEDED", output, System.currentTimeMillis() - start);
            return response;
        } catch (RuntimeException e) {
            log.warn("模型调用失败 runId={}: {}", context.getRunId(), e.getMessage());
            context.finishStep(seq, "FAILED", e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    private String summarizeRequest(ModelRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        return truncate(request.getMessages().get(request.getMessages().size() - 1).getText(), 2000);
    }

    private String summarizeResponse(ModelResponse response) {
        if (response == null) {
            return "";
        }
        Object message = response.getMessage();
        if (message instanceof AssistantMessage assistantMessage) {
            return truncate(assistantMessage.getText(), 4000);
        }
        return truncate(String.valueOf(message), 2000);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
