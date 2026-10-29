package com.agentforge.agent.runtime;

import com.agentforge.agent.domain.AgentType;
import com.agentforge.agent.entity.AgentDefinitionEntity;
import com.agentforge.agent.repository.AgentDefinitionRepository;
import com.agentforge.agent.tool.AgentToolRegistry;
import com.agentforge.agent.tool.CalculatorTool;
import com.agentforge.audit.gateway.ModelProviderFactory;
import com.agentforge.audit.quota.QuotaService;
import com.agentforge.audit.quota.QuotaStatus;
import com.agentforge.common.exception.QuotaExceededException;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.agentforge.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 不依赖 Mockito（本机 JDK21 下 inline mock-maker 自附加受限），用 JDK 动态代理桩掉仓储与 ObjectProvider。
 */
class ReactAgentFactoryTest {

    private final AgentToolRegistry toolRegistry = AgentToolRegistry.of(new CalculatorTool());

    @Test
    void buildsReactAgentFromDefinition() {
        AgentDefinitionEntity definition = definition(1L, "assistant", AgentType.SINGLE, List.of("calculator"));
        definition.setSystemPrompt("你是助手");
        definition.setMaxIterations(4);
        RunContext context = context();
        Agent agent = factory(List.of()).build(definition, context);

        assertThat(agent).isInstanceOf(ReactAgent.class);
        assertThat(agent.name()).isEqualTo("assistant");
        assertThat(context.getModelName()).isEqualTo("mock-chat");
    }

    @Test
    void quotaGuardBlocksModelCall() {
        AgentDefinitionEntity definition = definition(1L, "assistant", AgentType.SINGLE, List.of());
        ReactAgentFactory factory = new ReactAgentFactory(toolRegistry, repositoryStub(List.of()),
                providerStub(null), providerStub(com.agentforge.agent.support.QuotaServiceStubs.alwaysExceeded()),
                new ObjectMapper());
        RunContext context = context();
        Agent agent = factory.build(definition, context);

        // SAA 的 AgentLlmNode 会把模型异常吞成 AssistantMessage，额度异常以运行上下文留痕为准
        try {
            ((ReactAgent) agent).call("你好");
        } catch (Exception ignored) {
            // 不同 SAA 版本可能直接抛出，两种行为都算额度拦截生效
        }
        assertThat(context.getQuotaError()).isNotNull();
        assertThat(context.getQuotaError().getCode()).isEqualTo(42901);
    }

    @Test
    void buildsSequentialAgentWithSubAgents() {
        AgentDefinitionEntity first = definition(10L, "first", AgentType.SINGLE, List.of());
        AgentDefinitionEntity second = definition(11L, "second", AgentType.SINGLE, List.of());
        AgentDefinitionEntity flow = definition(1L, "pipeline", AgentType.SEQUENTIAL, List.of());
        flow.setSubAgentIdsJson("[10,11]");

        Agent agent = factory(List.of(second, first)).build(flow, context());
        assertThat(agent).isInstanceOf(SequentialAgent.class);
        assertThat(agent.name()).isEqualTo("pipeline");
    }

    @Test
    void buildsParallelAgent() {
        AgentDefinitionEntity first = definition(10L, "first", AgentType.SINGLE, List.of());
        AgentDefinitionEntity second = definition(11L, "second", AgentType.SINGLE, List.of());
        AgentDefinitionEntity flow = definition(1L, "parallel", AgentType.PARALLEL, List.of());
        flow.setSubAgentIdsJson("[10,11]");

        assertThat(factory(List.of(first, second)).build(flow, context())).isInstanceOf(ParallelAgent.class);
    }

    @Test
    void parallelWithSingleSubAgentFails() {
        AgentDefinitionEntity first = definition(10L, "first", AgentType.SINGLE, List.of());
        AgentDefinitionEntity flow = definition(1L, "parallel", AgentType.PARALLEL, List.of());
        flow.setSubAgentIdsJson("[10]");

        assertThatThrownBy(() -> factory(List.of(first)).build(flow, context()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("两个子 Agent");
    }

    @Test
    void buildsSupervisorAsLlmRoutingAgent() {
        AgentDefinitionEntity first = definition(10L, "first", AgentType.SINGLE, List.of());
        AgentDefinitionEntity flow = definition(1L, "supervisor", AgentType.SUPERVISOR, List.of());
        flow.setSubAgentIdsJson("[10]");

        assertThat(factory(List.of(first)).build(flow, context())).isInstanceOf(LlmRoutingAgent.class);
    }

    @Test
    void supervisorWithoutSubAgentsFails() {
        AgentDefinitionEntity flow = definition(1L, "supervisor", AgentType.SUPERVISOR, List.of());
        flow.setSubAgentIdsJson("[]");
        assertThatThrownBy(() -> factory(List.of()).build(flow, context()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("子 Agent");
    }

    private ReactAgentFactory factory(List<AgentDefinitionEntity> subAgents) {
        return new ReactAgentFactory(toolRegistry, repositoryStub(subAgents), providerStub(null),
                providerStub(null), new ObjectMapper());
    }

    private AgentDefinitionRepository repositoryStub(List<AgentDefinitionEntity> subAgents) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "findByIdInAndTenantIdAndDeletedFalse" -> subAgents;
            case "toString" -> "AgentDefinitionRepositoryStub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return (AgentDefinitionRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{AgentDefinitionRepository.class}, handler);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerStub(T bean) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getIfAvailable", "getIfUnique", "getObject" -> bean;
            case "toString" -> "ObjectProviderStub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return (ObjectProvider<T>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ObjectProvider.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == int.class || type == short.class || type == byte.class) {
            return 0;
        }
        return '\0';
    }

    private RunContext context() {
        return new RunContext(1L, 1L, "session-1", 1L, 1L, false, null);
    }

    private AgentDefinitionEntity definition(Long id, String name, AgentType type, List<String> tools) {
        AgentDefinitionEntity definition = new AgentDefinitionEntity();
        definition.setId(id);
        definition.setTenantId(1L);
        definition.setName(name);
        definition.setType(type.name());
        definition.setEnabled(true);
        definition.setCurrentVersion(1);
        definition.setMaxIterations(5);
        definition.setToolsJson(tools.isEmpty() ? "[]" : "[\"" + String.join("\",\"", tools) + "\"]");
        return definition;
    }
}
