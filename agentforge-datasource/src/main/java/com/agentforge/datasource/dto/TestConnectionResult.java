package com.agentforge.datasource.dto;

/** 连接测试结果：是否连通、连接加 SELECT 1 的耗时、说明（不含密码等敏感信息）。 */
public record TestConnectionResult(boolean success, long latencyMs, String message) {
}
