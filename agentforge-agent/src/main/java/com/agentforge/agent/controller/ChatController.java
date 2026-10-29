package com.agentforge.agent.controller;

import com.agentforge.agent.dto.ChatStreamRequest;
import com.agentforge.agent.service.AgentChatService;
import com.agentforge.audit.quota.QuotaService;
import com.agentforge.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 对话（契约 §2.6：事件 run.started / message.delta / tool.* / usage / done）。
 * 建立 SSE 前先做一次额度预检，超额直接由全局异常处理器返回 429/42901。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ChatController {

    private final AgentChatService chatService;
    private final ObjectProvider<QuotaService> quotaServiceProvider;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('agent:run')")
    public SseEmitter stream(@RequestBody ChatStreamRequest request) {
        assertQuota();
        return chatService.chat(request);
    }

    private void assertQuota() {
        QuotaService quotaService = quotaServiceProvider.getIfAvailable();
        if (quotaService != null) {
            quotaService.assertCanConsume(SecurityUtils.currentUserIdOrThrow());
        }
    }
}
