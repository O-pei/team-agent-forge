package com.agentforge.agent.controller;

import com.agentforge.agent.dto.ChatStreamRequest;
import com.agentforge.agent.service.AgentChatService;
import com.agentforge.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 不依赖 Mockito：用匿名子类替身覆盖 chat 行为。
 */
class ChatControllerTest {

    @Test
    void delegatesToServiceAndReturnsEmitter() {
        SseEmitter emitter = new SseEmitter();
        AgentChatService service = new AgentChatService(null, null, null) {
            @Override
            public SseEmitter chat(ChatStreamRequest request) {
                return emitter;
            }
        };
        ChatController controller = new ChatController(service);

        assertThat(controller.stream(new ChatStreamRequest(1L, null, "你好", null))).isSameAs(emitter);
    }

    @Test
    void surfacesServiceValidationError() {
        AgentChatService service = new AgentChatService(null, null, null) {
            @Override
            public SseEmitter chat(ChatStreamRequest request) {
                throw BizException.badRequest("agentId 不能为空");
            }
        };
        ChatController controller = new ChatController(service);

        assertThatThrownBy(() -> controller.stream(new ChatStreamRequest(null, null, "hi", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("agentId");
    }
}
