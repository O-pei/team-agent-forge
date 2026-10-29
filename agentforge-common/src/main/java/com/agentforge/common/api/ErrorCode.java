package com.agentforge.common.api;

/**
 * 通用错误码。业务模块可在此基础上扩展约定，但对外统一返回 {@code code + message}。
 */
public enum ErrorCode {

    SUCCESS(0, "OK"),

    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或凭证已失效"),
    FORBIDDEN(40300, "无权访问该资源"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "资源状态冲突"),
    TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后再试"),

    INTERNAL_ERROR(50000, "服务器内部错误"),
    SERVICE_UNAVAILABLE(50300, "服务暂不可用");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
