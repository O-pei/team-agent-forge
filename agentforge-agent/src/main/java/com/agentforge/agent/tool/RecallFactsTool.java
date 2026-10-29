package com.agentforge.agent.tool;

import com.agentforge.agent.runtime.RunContext;
import com.agentforge.agent.runtime.RunContextHolder;
import com.agentforge.common.context.TenantContext;
import com.agentforge.common.context.UserContext;
import com.agentforge.memory.spi.LongTermFact;
import com.agentforge.memory.spi.LongTermMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 长期记忆召回工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecallFactsTool implements AgentToolDefinition {

    private final ObjectProvider<LongTermMemory> memoryProvider;

    @Override
    public String name() {
        return ToolNames.RECALL_FACTS;
    }

    @Override
    public String description() {
        return "根据关键词召回之前记住的长期事实";
    }

    @Override
    public String riskLevel() {
        return "LOW";
    }

    @Override
    public Object toolBean() {
        return this;
    }

    @Tool(name = ToolNames.RECALL_FACTS, description = "根据关键词召回当前用户之前被记住的长期事实")
    public String recallFacts(
            @ToolParam(description = "查询关键词") String query,
            @ToolParam(description = "返回条数，默认 5，最大 20", required = false) Integer topK) {
        if (query == null || query.isBlank()) {
            return "召回失败: query 不能为空";
        }
        LongTermMemory memory = memoryProvider.getIfAvailable();
        if (memory == null) {
            return "长期记忆服务尚未配置（memory 模块未就绪），无法召回。";
        }
        Long tenantId = currentTenantId();
        Long userId = currentUserId();
        if (userId == null) {
            return "召回失败: 无法识别当前用户";
        }
        int limit = topK == null || topK <= 0 ? 5 : Math.min(topK, 20);
        try {
            List<LongTermFact> facts = memory.recall(tenantId, userId, query, limit);
            if (facts == null || facts.isEmpty()) {
                return "没有召回相关的长期记忆。";
            }
            StringBuilder sb = new StringBuilder("召回 ").append(facts.size()).append(" 条长期记忆：");
            for (LongTermFact fact : facts) {
                sb.append("\n- [").append(fact.category()).append("] ").append(fact.fact())
                        .append(" (score=").append(fact.score()).append(")");
            }
            return sb.toString();
        } catch (RuntimeException e) {
            log.warn("recall_facts 调用失败: {}", e.getMessage());
            return "记忆召回失败: " + e.getMessage();
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
