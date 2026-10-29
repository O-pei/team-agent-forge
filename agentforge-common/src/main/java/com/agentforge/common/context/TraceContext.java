package com.agentforge.common.context;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * TraceId 上下文：由 TraceIdFilter 写入，供日志 / 响应包装读取。
 */
public final class TraceContext {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String TRACE_MDC_KEY = "traceId";

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String getTraceId() {
        return HOLDER.get();
    }

    public static void setTraceId(String traceId) {
        HOLDER.set(traceId);
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 只接受调用方传入的安全 traceId；其余情况生成新的 UUID。
     */
    public static String currentOrCreate(String candidate) {
        if (candidate != null && SAFE_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
