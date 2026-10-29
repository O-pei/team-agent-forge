package com.agentforge.datasource.dto;

/**
 * 连接测试结果。
 *
 * @param success   是否连通
 * @param latencyMs 连接 + SELECT 1 耗时
 * @param message   结果说明（不含密码等敏感信息）
 */
public record TestConnectionResult(boolean success, long latencyMs, String message) {
}
