package com.agentforge.audit.usage;

/**
 * 模型调用上下文（ThreadLocal），由 agent 模块在运行开始前 {@link #open} 打开、结束后 {@link #close} 关闭。
 *
 * <p>用法：</p>
 * <pre>{@code
 * UsageContext.open(tenantId, userId, agentId, runId, sessionId);
 * try {
 *     // 调用 ModelProviderFactory 得到的 ChatModel
 * } finally {
 *     UsageContext.close();
 * }
 * }</pre>
 *
 * <p>未打开时审计链路仍可工作：tenant/user 从 common 的 {@code TenantContext/UserContext} 兜底，
 * agent/run/session 为 null。</p>
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
