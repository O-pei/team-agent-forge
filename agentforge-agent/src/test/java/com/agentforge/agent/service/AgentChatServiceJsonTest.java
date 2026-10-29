package com.agentforge.agent.service;

import com.agentforge.agent.event.AgentEvent;
import com.agentforge.agent.event.AgentEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 事件 JSON 契约：data 中必须带 type（与 event 名一致），并保留契约字段。
 */
class AgentChatServiceJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentChatService service = new AgentChatService(null, objectMapper, null);

    @Test
    void messageDeltaCarriesTypeAndContent() throws Exception {
        JsonNode node = objectMapper.readTree(
                service.toJson(AgentEvent.MESSAGE_DELTA, new AgentEvents.MessageDelta("你好")));
        assertThat(node.get("type").asText()).isEqualTo("message.delta");
        assertThat(node.get("content").asText()).isEqualTo("你好");
    }

    @Test
    void runStartedCarriesTypeAndContractFields() throws Exception {
        JsonNode node = objectMapper.readTree(service.toJson(AgentEvent.RUN_STARTED,
                new AgentEvents.RunStarted(1L, 2L, "session-1", "mock-chat")));
        assertThat(node.get("type").asText()).isEqualTo("run.started");
        assertThat(node.get("runId").asLong()).isEqualTo(1L);
        assertThat(node.get("agentId").asLong()).isEqualTo(2L);
        assertThat(node.get("sessionId").asText()).isEqualTo("session-1");
        assertThat(node.get("model").asText()).isEqualTo("mock-chat");
    }

    @Test
    void usageCarriesTokenFields() throws Exception {
        JsonNode node = objectMapper.readTree(service.toJson(AgentEvent.USAGE,
                new AgentEvents.Usage("mock-chat", 12L, 34L, 46L, 0.0d)));
        assertThat(node.get("type").asText()).isEqualTo("usage");
        assertThat(node.get("promptTokens").asLong()).isEqualTo(12L);
        assertThat(node.get("completionTokens").asLong()).isEqualTo(34L);
        assertThat(node.get("totalTokens").asLong()).isEqualTo(46L);
    }

    @Test
    void errorCarriesNumericCode() throws Exception {
        JsonNode node = objectMapper.readTree(
                service.toJson(AgentEvent.ERROR, new AgentEvents.ErrorData(50000, "boom")));
        assertThat(node.get("code").asInt()).isEqualTo(50000);
        assertThat(node.get("message").asText()).isEqualTo("boom");
    }
}
