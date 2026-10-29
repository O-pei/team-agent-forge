package com.agentforge.datasource.dto;

import com.agentforge.datasource.spi.SandboxRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.util.Map;

/**
 * 沙箱执行请求（契约 §4 sandbox execute）。
 */
public record SandboxExecuteRequest(
        @NotBlank(message = "语言不能为空")
        @Pattern(regexp = "(?i)java|python|node|shell", message = "语言仅支持 java/python/node/shell")
        String language,

        @NotBlank(message = "代码不能为空")
        @Size(max = 100_000, message = "代码最长 100KB")
        String code,

        Integer timeoutSeconds,

        Map<String, String> env
) {

    public SandboxRequest toSandboxRequest() {
        int seconds = timeoutSeconds == null ? 10 : Math.max(1, Math.min(timeoutSeconds, 30));
        return new SandboxRequest(language, code, Duration.ofSeconds(seconds), env);
    }
}
