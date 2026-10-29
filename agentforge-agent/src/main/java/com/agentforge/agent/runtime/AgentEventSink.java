package com.agentforge.agent.runtime;

/**
 * SSE 事件出口：生产环境接 {@code SseEmitter}，单测里接收集列表。
 */
@FunctionalInterface
public interface AgentEventSink {

    void send(String event, Object data);
}
