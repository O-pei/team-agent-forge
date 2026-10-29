package com.agentforge.common.context;

/**
 * 租户上下文（ThreadLocal）。v0.1.0 单租户默认租户 ID 为 1，为后续多租户预留。
 */
public final class TenantContext {

    public static final long DEFAULT_TENANT_ID = 1L;

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Long getTenantId() {
        return HOLDER.get();
    }

    public static long getCurrentTenantId() {
        Long tenantId = HOLDER.get();
        return tenantId == null ? DEFAULT_TENANT_ID : tenantId;
    }

    public static void setTenantId(Long tenantId) {
        HOLDER.set(tenantId);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
