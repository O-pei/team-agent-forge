package com.agentforge.agent.runtime;

import com.agentforge.agent.domain.AgentType;
import com.agentforge.agent.entity.AgentDefinitionEntity;
import com.agentforge.agent.repository.AgentDefinitionRepository;
import com.agentforge.agent.tool.AgentToolRegistry;
import com.agentforge.audit.gateway.ChatModelInfo;
import com.agentforge.audit.gateway.ModelProviderFactory;
import com.agentforge.audit.gateway.mock.MockChatModel;
import com.agentforge.audit.quota.QuotaService;
import com.agentforge.common.exception.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 从数据库配置构建 SAA Agent（ReactAgent 或 FlowAgent 编排）。
 * 模型来自 ModelProviderFactory，无实现或解析失败时降级为 MockChatModel，
 * 保证零配置环境可用；saver 用 MemorySaver（无 Redis 环境）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactAgentFactory {

    private static final int MAX_DEPTH = 3;
    private static final int DEFAULT_MAX_ITERATIONS = 10;

    private final AgentToolRegistry toolRegistry;
    private final AgentDefinitionRepository definitionRepository;
    private final ObjectProvider<ModelProviderFactory> modelProviderFactoryProvider;
    private final ObjectProvider<QuotaService> quotaServiceProvider;
    private final ObjectMapper objectMapper;

    public Agent build(AgentDefinitionEntity definition, RunContext context) {
        return build(definition, context, 0);
    }

    private Agent build(AgentDefinitionEntity definition, RunContext context, int depth) {
        if (depth > MAX_DEPTH) {
            throw BizException.badRequest("子 Agent 嵌套层级超过 " + MAX_DEPTH + " 层");
        }
        AgentType type = AgentType.from(definition.getType());
        ChatModel model = resolveChatModel(definition, context);
        MemorySaver saver = new MemorySaver();
        return switch (type) {
            case SINGLE -> buildReactAgent(definition, context, model, saver);
            case SEQUENTIAL -> SequentialAgent.builder()
                    .name(agentName(definition))
                    .description(nullToEmpty(definition.getDescription()))
                    .saver(saver)
                    .subAgents(loadSubAgents(definition, context, depth))
                    .build();
            case PARALLEL -> buildParallel(definition, context, saver, depth);
            case ROUTING -> LlmRoutingAgent.builder()
                    .name(agentName(definition))
                    .description(nullToEmpty(definition.getDescription()))
                    .model(model)
                    .systemPrompt(orDefault(definition.getSystemPrompt(),
                            "你是路由器，根据用户请求选择最合适的子 Agent，并只调用一个子 Agent 完成任务。"))
                    .subAgents(loadSubAgents(definition, context, depth))
                    .saver(saver)
                    .build();
            case SUPERVISOR -> buildSupervisor(definition, context, model, saver, depth);
        };
    }

    private Agent buildParallel(AgentDefinitionEntity definition, RunContext context,
                                MemorySaver saver, int depth) {
        List<Agent> subAgents = loadSubAgents(definition, context, depth);
        if (subAgents.size() < 2) {
            throw BizException.badRequest("PARALLEL 类型至少需要两个子 Agent");
        }
        return ParallelAgent.builder()
                .name(agentName(definition))
                .description(nullToEmpty(definition.getDescription()))
                .saver(saver)
                .subAgents(subAgents)
                .maxConcurrency(subAgents.size())
                .mergeOutputKey("merged_results")
                .build();
    }

    /**
     * SAA 1.1.2.4 没有原生 SupervisorAgent，这里用 LlmRoutingAgent 近似：
     * 由主管模型选择子 Agent，并 fallback 到第一个子 Agent。
     */
    private Agent buildSupervisor(AgentDefinitionEntity definition, RunContext context, ChatModel model,
                                  MemorySaver saver, int depth) {
        List<Agent> subAgents = loadSubAgents(definition, context, depth);
        if (subAgents.isEmpty()) {
            throw BizException.badRequest("SUPERVISOR 类型至少需要配置一个子 Agent");
        }
        return LlmRoutingAgent.builder()
                .name(agentName(definition))
                .description(nullToEmpty(definition.getDescription()))
                .model(model)
                .systemPrompt(orDefault(definition.getSystemPrompt(),
                        "你是主管 Agent，负责把用户任务分派给最合适的子 Agent 执行，并汇总结果回复用户。"))
                .subAgents(subAgents)
                .fallbackAgent(subAgents.getFirst().name())
                .saver(saver)
                .build();
    }

    private ReactAgent buildReactAgent(AgentDefinitionEntity definition, RunContext context,
                                       ChatModel model, MemorySaver saver) {
        Builder builder = ReactAgent.builder()
                .name(agentName(definition))
                .description(nullToEmpty(definition.getDescription()))
                .model(model)
                .saver(saver)
                .interceptors(List.of(new RunModelInterceptor(context), new RunToolInterceptor(context, toolRegistry)));

        String systemPrompt = definition.getSystemPrompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.systemPrompt(systemPrompt);
        }
        List<Object> toolBeans = toolRegistry.resolve(toolNames(definition));
        if (!toolBeans.isEmpty()) {
            builder.methodTools(toolBeans.toArray());
        }
        int maxIterations = definition.getMaxIterations() == null || definition.getMaxIterations() <= 0
                ? DEFAULT_MAX_ITERATIONS : definition.getMaxIterations();
        builder.hooks(List.of(ModelCallLimitHook.builder().runLimit(maxIterations).build()));
        if (definition.getTemperature() != null) {
            // 注意：Spring AI 的 DefaultChatOptions 缺少 @JsonProperty 字段，
            // SAA DefaultBuilder 合并 ChatOptions 时会抛 IllegalArgumentException，
            // 因此使用 DashScopeChatOptions（Jackson 可序列化，Mock 模型会忽略该选项）。
            DashScopeChatOptions options = new DashScopeChatOptions();
            options.setTemperature(definition.getTemperature());
            builder.chatOptions(options);
        }
        Class<?> outputType = resolveOutputType(definition.getOutputType());
        if (outputType != null) {
            builder.outputType(outputType);
        }
        return builder.build();
    }

    private List<Agent> loadSubAgents(AgentDefinitionEntity definition, RunContext context, int depth) {
        List<Long> ids = subAgentIds(definition);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AgentDefinitionEntity> subDefinitions =
                definitionRepository.findByIdInAndTenantIdAndDeletedFalse(ids, definition.getTenantId());
        java.util.Map<Long, AgentDefinitionEntity> byId = new java.util.HashMap<>();
        for (AgentDefinitionEntity subDefinition : subDefinitions) {
            byId.put(subDefinition.getId(), subDefinition);
        }
        // 按配置顺序返回，避免数据库返回顺序不稳定
        List<Agent> agents = new ArrayList<>();
        for (Long id : ids) {
            AgentDefinitionEntity subDefinition = byId.get(id);
            if (subDefinition == null) {
                log.warn("子 Agent 不存在或不属于当前租户，已跳过: id={}", id);
                continue;
            }
            agents.add(build(subDefinition, context, depth + 1));
        }
        return agents;
    }

    private ChatModel resolveChatModel(AgentDefinitionEntity definition, RunContext context) {
        ModelProviderFactory factory = modelProviderFactoryProvider.getIfAvailable();
        ChatModel model = null;
        if (factory != null) {
            try {
                model = definition.getModelId() == null
                        ? factory.getDefaultChatModel() : factory.getChatModel(definition.getModelId());
                ChatModelInfo info = definition.getModelId() == null
                        ? factory.getModelInfo(factory.getDefaultModelId())
                        : factory.getModelInfo(definition.getModelId());
                if (info != null) {
                    context.setModelName(info.modelName());
                }
            } catch (RuntimeException e) {
                log.warn("解析模型失败，降级为 Mock 模型 modelId={}: {}",
                        definition.getModelId(), e.getMessage());
            }
        }
        if (model == null) {
            model = new MockChatModel("mock-chat");
            context.setModelName("mock-chat");
        }
        // 每次模型调用前复检额度，超额抛 42901，并记录到运行上下文
        model = QuotaGuardChatModel.wrapIfNecessary(model, context.getUserId(),
                quotaServiceProvider.getIfAvailable(), context::setQuotaError);
        // SAA 合并 ChatOptions 时要求 Jackson 可序列化，默认的 DefaultChatOptions 不满足
        return JacksonSafeChatModel.wrapIfNecessary(model);
    }

    private static String agentName(AgentDefinitionEntity definition) {
        return definition.getName() == null ? "agent-" + definition.getId() : definition.getName();
    }

    /** 输出类型只允许从当前类加载路径解析。 */
    private Class<?> resolveOutputType(String outputType) {
        if (outputType == null || outputType.isBlank()) {
            return null;
        }
        try {
            return Class.forName(outputType.trim());
        } catch (ClassNotFoundException e) {
            log.warn("outputType 类不存在，忽略: {}", outputType);
            return null;
        }
    }

    private List<String> toolNames(AgentDefinitionEntity definition) {
        return parseStringList(definition.getToolsJson());
    }

    private List<Long> subAgentIds(AgentDefinitionEntity definition) {
        List<String> raw = parseStringList(definition.getSubAgentIdsJson());
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String value : raw) {
            try {
                ids.add(Long.parseLong(value.trim()));
            } catch (NumberFormatException e) {
                log.warn("忽略非法子 Agent id: {}", value);
            }
        }
        return ids;
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return values == null ? List.of() : values;
        } catch (Exception e) {
            log.warn("JSON 数组解析失败，按逗号分隔处理: {}", json);
            return Arrays.stream(json.replace("[", "").replace("]", "").replace("\"", "").split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    private static String orDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
