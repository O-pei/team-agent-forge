package com.agentforge.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中的 RunContext 注册表，用于取消运行。
 */
@Component
public class RunRegistry {

    private final Map<Long, RunContext> running = new ConcurrentHashMap<>();

    public void register(RunContext context) {
        if (context != null && context.getRunId() != null) {
            running.put(context.getRunId(), context);
        }
    }

    public void unregister(Long runId) {
        if (runId != null) {
            running.remove(runId);
        }
    }

    public boolean cancel(Long runId) {
        RunContext context = running.get(runId);
        if (context == null) {
            return false;
        }
        context.cancel();
        return true;
    }

    public Optional<RunContext> get(Long runId) {
        return Optional.ofNullable(running.get(runId));
    }
}
