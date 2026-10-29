package com.agentforge.common.api;

import com.agentforge.common.context.TraceContext;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 统一 API 响应包装：业务码、提示信息、业务数据、traceId 与服务端时间戳。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(int code, String message, T data, String traceId, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getDefaultMessage(),
                data, TraceContext.getTraceId(), Instant.now());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getDefaultMessage());
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode.getCode(), message);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, TraceContext.getTraceId(), Instant.now());
    }
}
