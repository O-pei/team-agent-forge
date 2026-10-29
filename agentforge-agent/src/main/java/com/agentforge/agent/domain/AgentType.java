package com.agentforge.agent.domain;

import com.agentforge.common.exception.BizException;

import java.util.Locale;

/**
 * Agent 类型。SINGLE 为单 ReactAgent；其余为 SAA FlowAgent 编排。
 */
public enum AgentType {

    /** 单智能体（ReactAgent + 工具） */
    SINGLE,
    /** 顺序编排：子 Agent 依次执行 */
    SEQUENTIAL,
    /** 并行编排：子 Agent 并发执行后合并 */
    PARALLEL,
    /** 路由编排：由 LLM 决定交给哪个子 Agent */
    ROUTING,
    /**
     * 监督者编排：v0.1 用 SAA LlmRoutingAgent 近似（SAA 1.1.2.4 无 SupervisorAgent）。
     */
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
