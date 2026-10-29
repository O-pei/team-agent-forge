package com.agentforge.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 四则运算工具（纯本地计算，无 Bean 依赖）。
 * 注意 SAA 工具签名限制：入参使用 String（非原始类型 / Optional / 集合）。
 */
@Component
public class CalculatorTool implements AgentToolDefinition {

    @Override
    public String name() {
        return ToolNames.CALCULATOR;
    }

    @Override
    public String description() {
        return "计算四则运算表达式，支持 + - * / % 与括号";
    }

    @Override
    public String riskLevel() {
        return "LOW";
    }

    @Override
    public Object toolBean() {
        return this;
    }

    @Tool(name = ToolNames.CALCULATOR, description = "计算四则运算表达式，支持 + - * / % 与括号，例如 (12+30)*2")
    public String calculate(
            @ToolParam(description = "算术表达式，例如 (12+30)*2") String expression) {
        if (expression == null || expression.isBlank()) {
            return "计算失败: 表达式为空";
        }
        try {
            double value = new Parser(expression).parse();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return "计算失败: 结果非法";
            }
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        } catch (RuntimeException e) {
            return "计算失败: " + e.getMessage();
        }
    }

    /** 递归下降解析：expr = term (('+'|'-') term)* ; term = factor (('*'|'/'|'%') factor)* ; factor = number | '(' expr ')' */
    private static final class Parser {

        private final String input;
        private int pos;

        private Parser(String input) {
            this.input = input;
        }

        private double parse() {
            double value = expression();
            skipSpaces();
            if (pos < input.length()) {
                throw new IllegalArgumentException("无法识别字符 '" + input.charAt(pos) + "'（位置 " + pos + "）");
            }
            return value;
        }

        private double expression() {
            double value = term();
            while (true) {
                skipSpaces();
                if (consume('+')) {
                    value += term();
                } else if (consume('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
        }

        private double term() {
            double value = factor();
            while (true) {
                skipSpaces();
                if (consume('*')) {
                    value *= factor();
                } else if (consume('/')) {
                    double divisor = factor();
                    if (divisor == 0d) {
                        throw new IllegalArgumentException("除数不能为 0");
                    }
                    value /= divisor;
                } else if (consume('%')) {
                    double divisor = factor();
                    if (divisor == 0d) {
                        throw new IllegalArgumentException("取模除数不能为 0");
                    }
                    value %= divisor;
                } else {
                    return value;
                }
            }
        }

        private double factor() {
            skipSpaces();
            if (consume('(')) {
                double value = expression();
                skipSpaces();
                if (!consume(')')) {
                    throw new IllegalArgumentException("括号不匹配");
                }
                return value;
            }
            if (consume('-')) {
                return -factor();
            }
            if (consume('+')) {
                return factor();
            }
            return number();
        }

        private double number() {
            skipSpaces();
            int start = pos;
            boolean dot = false;
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("期望数字（位置 " + pos + "）");
            }
            return Double.parseDouble(input.substring(start, pos));
        }

        private boolean consume(char expected) {
            skipSpaces();
            if (pos < input.length() && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }
}
