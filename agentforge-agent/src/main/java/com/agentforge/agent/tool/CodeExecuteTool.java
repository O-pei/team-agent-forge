package com.agentforge.agent.tool;

import com.agentforge.datasource.spi.SandboxProvider;
import com.agentforge.datasource.spi.SandboxRequest;
import com.agentforge.datasource.spi.SandboxResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 代码执行工具：命令白名单、超时与输出上限由 SandboxProvider 实现负责。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeExecuteTool implements AgentToolDefinition {

    private final ObjectProvider<SandboxProvider> sandboxProvider;

    @Override
    public String name() {
        return ToolNames.CODE_EXECUTE;
    }

    @Override
    public String description() {
        return "在受限沙箱中执行 java/python/node 代码片段";
    }

    @Override
    public String riskLevel() {
        return "HIGH";
    }

    @Override
    public Object toolBean() {
        return this;
    }

    @Tool(name = ToolNames.CODE_EXECUTE, description = "在受限沙箱中执行代码片段并返回 stdout/stderr/退出码")
    public String codeExecute(
            @ToolParam(description = "语言：java / python / node") String language,
            @ToolParam(description = "要执行的代码或脚本") String code) {
        if (language == null || language.isBlank()) {
            return "执行失败: 必须提供 language";
        }
        if (code == null || code.isBlank()) {
            return "执行失败: code 不能为空";
        }
        SandboxProvider provider = sandboxProvider.getIfAvailable();
        if (provider == null) {
            return "代码沙箱尚未配置（数据源模块未就绪），无法执行代码。";
        }
        try {
            SandboxResult result = provider.execute(
                    new SandboxRequest(language.trim().toLowerCase(), code, Duration.ofSeconds(10), Map.of()));
            StringBuilder sb = new StringBuilder();
            sb.append("success=").append(result.success())
                    .append(", exitCode=").append(result.exitCode())
                    .append(", elapsedMs=").append(result.elapsedMs())
                    .append(", truncated=").append(result.truncated());
            if (result.stdout() != null && !result.stdout().isBlank()) {
                sb.append("\nstdout:\n").append(result.stdout());
            }
            if (result.stderr() != null && !result.stderr().isBlank()) {
                sb.append("\nstderr:\n").append(result.stderr());
            }
            return sb.toString();
        } catch (RuntimeException e) {
            log.warn("code_execute 调用失败: {}", e.getMessage());
            return "代码执行失败: " + e.getMessage();
        }
    }
}
