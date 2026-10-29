package com.agentforge.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorToolTest {

    private final CalculatorTool calculator = new CalculatorTool();

    @Test
    void evaluatesArithmeticExpression() {
        assertThat(calculator.calculate("(12+30)*2")).isEqualTo("84");
        assertThat(calculator.calculate("10/4")).isEqualTo("2.5");
        assertThat(calculator.calculate("-3 + 5 * 2")).isEqualTo("7");
    }

    @Test
    void rejectsInvalidExpression() {
        assertThat(calculator.calculate("1 + ")).contains("计算失败");
        assertThat(calculator.calculate("abc")).contains("计算失败");
    }

    @Test
    void divisionByZeroIsReported() {
        assertThat(calculator.calculate("1/0")).contains("计算失败");
    }

    @Test
    void exposesDefinitionMetadata() {
        assertThat(calculator.name()).isEqualTo(ToolNames.CALCULATOR);
        assertThat(calculator.riskLevel()).isEqualTo("LOW");
        assertThat(calculator.toolBean()).isSameAs(calculator);
    }
}
