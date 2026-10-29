package com.agentforge.agent.tool;

import com.agentforge.agent.dto.ToolInfo;

/**
 * 可注册工具的元信息。每个工具 Bean 实现本接口，由 {@link AgentToolRegistry} 汇总。
 */
public interface AgentToolDefinition {

    /** 模型可见的 tool name，取值见 {@link ToolNames}。 */
    String name();

    String description();

    /** LOW / MEDIUM / HIGH，用于 SSE tool.call 与 /api/v1/tools 展示。 */
    String riskLevel();

    /** 含 @Tool 方法的 Bean 实例，传给 ReactAgent#methodTools。 */
    Object toolBean();

    default ToolInfo toInfo() {
        return new ToolInfo(name(), description(), riskLevel(), "builtin");
    }
}
