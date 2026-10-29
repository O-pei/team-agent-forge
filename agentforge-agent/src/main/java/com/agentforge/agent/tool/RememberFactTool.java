package com.agentforge.agent.tool;

import com.agentforge.agent.runtime.RunContext;
import com.agentforge.agent.runtime.RunContextHolder;
import com.agentforge.common.context.TenantContext;
import com.agentforge.common.context.UserContext;
import com.agentforge.memory.spi.LongTermMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 长期记忆写入工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RememberFactTool implements AgentToolDefinition {

    private final ObjectProvider<LongTermMemory> memoryProvider;

    @Override
    public String name() {
        return ToolNames.REMEMBER_FACT;
    }

    @Override
    public String description() {
        return "记住一条关于用户的长期事实或偏好";
    }

    @Override
    public String riskLevel() {
        return "MEDIUM";
    }

    @Override
    public Object toolBean() {
        return this;
    }

    @Tool(name = ToolNames.REMEMBER_FACT, description = "记住一条关于当前用户的长期事实或偏好，供后续会话召回")
    public String rememberFact(
            @ToolParam(description = "要长期记住的事实内容") String fact,
            @ToolParam(description = "分类，如 preference/profile/task，默认 general", required = false) String category) {
        if (fact == null || fact.isBlank()) {
            return "记忆失败: fact 不能为空";
        }
        LongTermMemory memory = memoryProvider.getIfAvailable();
        if (memory == null) {
            return "长期记忆服务尚未配置（memory 模块未就绪），本条事实未保存。";
        }
        Long tenantId = currentTenantId();
        Long userId = currentUserId();
        if (userId == null) {
            return "记忆失败: 无法识别当前用户";
        }
        try {
            memory.saveFact(tenantId, userId, fact.trim(),
                    category == null || category.isBlank() ? "general" : category.trim(), 0.8d);
            return "已记住: " + fact.trim();
        } catch (RuntimeException e) {
            log.warn("remember_fact 调用失败: {}", e.getMessage());
            return "记忆保存失败: " + e.getMessage();
        }
    }

    private Long currentTenantId() {
        RunContext context = RunContextHolder.current();
        if (context != null && context.getTenantId() != null) {
            return context.getTenantId();
        }
        return TenantContext.getCurrentTenantId();
    }

    private Long currentUserId() {
        RunContext context = RunContextHolder.current();
        if (context != null && context.getUserId() != null) {
            return context.getUserId();
        }
        return UserContext.getUserIdOrNull();
    }
}
