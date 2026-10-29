package com.agentforge.agent.tool;

import com.agentforge.agent.dto.ToolInfo;
import com.agentforge.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表：汇总所有 AgentToolDefinition Bean，按名称解析给 ReactAgent。
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentToolDefinition> definitions = new LinkedHashMap<>();

    public AgentToolRegistry(List<AgentToolDefinition> tools) {
        for (AgentToolDefinition tool : tools) {
            definitions.put(tool.name(), tool);
        }
    }

    /** 供单测直接构造（无 Spring 容器）。 */
    public static AgentToolRegistry of(AgentToolDefinition... tools) {
        return new AgentToolRegistry(List.of(tools));
    }

    /** 按名称解析出工具 Bean，同一 Bean 只返回一次。 */
    public List<Object> resolve(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        Set<Object> beans = new LinkedHashSet<>();
        for (String name : names) {
            AgentToolDefinition definition = definitions.get(name);
            if (definition == null) {
                throw BizException.badRequest("未注册的工具: " + name + "，可用工具: " + definitions.keySet());
            }
            beans.add(definition.toolBean());
        }
        return new ArrayList<>(beans);
    }

    public String riskLevel(String name) {
        AgentToolDefinition definition = definitions.get(name);
        return definition == null ? "LOW" : definition.riskLevel();
    }

    public boolean exists(String name) {
        return definitions.containsKey(name);
    }

    public List<ToolInfo> list() {
        return definitions.values().stream().map(AgentToolDefinition::toInfo).toList();
    }

    public Set<String> names() {
        return definitions.keySet();
    }
}
