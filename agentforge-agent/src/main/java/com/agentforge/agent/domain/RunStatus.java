package com.agentforge.agent.domain;

/**
 * 运行状态（与契约 SSE run.completed.status 一致）。
 */
public final class RunStatus {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private RunStatus() {
    }
}
