package com.agentforge.agent.controller;

import com.agentforge.agent.dto.ChatStreamRequest;
import com.agentforge.agent.service.AgentChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 对话（契约 §2.6：事件 run.started / message.delta / tool.* / usage / done）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ChatController {

    private final AgentChatService chatService;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('agent:run')")
    public SseEmitter stream(@RequestBody ChatStreamRequest request) {
        return chatService.chat(request);
    }
}
