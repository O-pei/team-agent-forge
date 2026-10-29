package com.agentforge.agent.runtime;

/**
 * SSE 事件出口：生产环境写入 SseEmitter，单测里写入收集列表。
 */
@FunctionalInterface
public interface AgentEventSink {

    void send(String event, Object data);
}
