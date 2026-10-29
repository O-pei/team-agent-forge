package com.agentforge.agent.event;

/**
 * 一条 SSE 事件：event 名称 + data 负载，字段遵循 docs/04-API契约.md §3。
 */
public record AgentEvent(String event, Object data) {

    public static final String RUN_STARTED = "run.started";
    public static final String MESSAGE_DELTA = "message.delta";
    public static final String MESSAGE_COMPLETED = "message.completed";
    public static final String TOOL_CALL = "tool.call";
    public static final String TOOL_RESULT = "tool.result";
    public static final String CITATION = "citation";
    public static final String USAGE = "usage";
    public static final String INTERRUPTED = "interrupted";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String ERROR = "error";
    public static final String PING = "ping";
}
