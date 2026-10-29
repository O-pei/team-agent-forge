package com.agentforge.audit.usage;

/**
 * 模型调用上下文（ThreadLocal）：agent 模块在运行前 open、结束后 close。
 * 未打开时审计链路仍可工作，tenant 和 user 从 common 的 TenantContext 与 UserContext 兜底，agent/run/session 为 null。
 */
public final class UsageContext {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private UsageContext() {
    }

    public static void open(Long tenantId, Long userId, Long agentId, Long runId, String sessionId) {
        HOLDER.set(new Context(tenantId, userId, agentId, runId, sessionId));
    }

    public static void open(Long tenantId, Long userId, String sessionId) {
        open(tenantId, userId, null, null, sessionId);
    }

    public static Context current() {
        return HOLDER.get();
    }

    public static void close() {
        HOLDER.remove();
    }

    public record Context(Long tenantId, Long userId, Long agentId, Long runId, String sessionId) {
    }
}
