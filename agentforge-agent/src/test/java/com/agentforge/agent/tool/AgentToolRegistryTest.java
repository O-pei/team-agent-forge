package com.agentforge.agent.tool;

import com.agentforge.agent.dto.ToolInfo;
import com.agentforge.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolRegistryTest {

    private final AgentToolRegistry registry = AgentToolRegistry.of(
            new CalculatorTool(), new CurrentTimeTool(), fake("sql_query", "HIGH"));

    @Test
    void resolvesRegisteredToolsInOrder() {
        List<Object> tools = registry.resolve(List.of("calculator", "sql_query", "calculator"));
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0)).isInstanceOf(CalculatorTool.class);
    }

    @Test
    void rejectsUnknownTool() {
        assertThatThrownBy(() -> registry.resolve(List.of("not_exists")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not_exists");
    }

    @Test
    void exposesToolMetadata() {
        assertThat(registry.exists("current_time")).isTrue();
        assertThat(registry.riskLevel("sql_query")).isEqualTo("HIGH");
        assertThat(registry.riskLevel("unknown")).isEqualTo("LOW");
        List<ToolInfo> tools = registry.list();
        assertThat(tools).hasSize(3);
        assertThat(tools).allMatch(info -> "builtin".equals(info.source()));
    }

    private static AgentToolDefinition fake(String name, String risk) {
        return new AgentToolDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public String riskLevel() {
                return risk;
            }

            @Override
            public Object toolBean() {
                return this;
            }
        };
    }
}
