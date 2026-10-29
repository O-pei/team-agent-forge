package com.agentforge.agent.runtime;

/**
 * 运行上下文线程绑定：工具（citation / 长期记忆）在 Agent 执行线程内读取当前运行信息。
 * 注意：流式执行可能跨线程，读取失败时工具应优雅降级。
 */
public final class RunContextHolder {

    private static final ThreadLocal<RunContext> HOLDER = new ThreadLocal<>();

    private RunContextHolder() {
    }

    public static void set(RunContext context) {
        HOLDER.set(context);
    }

    public static RunContext current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
