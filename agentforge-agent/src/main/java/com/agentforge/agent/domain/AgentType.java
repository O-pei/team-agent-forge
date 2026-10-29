package com.agentforge.agent.domain;

import com.agentforge.common.exception.BizException;

import java.util.Locale;

/**
 * Agent 类型：SINGLE 为单 ReactAgent，其余为 SAA FlowAgent 编排。
 * SAA 1.1.2.4 没有 SupervisorAgent，SUPERVISOR 用 LlmRoutingAgent 近似。
 */
public enum AgentType {

    SINGLE,
    SEQUENTIAL,
    PARALLEL,
    ROUTING,
    SUPERVISOR;

    public boolean isFlow() {
        return this != SINGLE;
    }

    public static AgentType from(String value) {
        if (value == null || value.isBlank()) {
            return SINGLE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("不支持的 Agent 类型: " + value);
        }
    }
}
