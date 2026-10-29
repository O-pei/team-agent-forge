package com.agentforge.datasource.spi;

/**
 * 沙箱执行结果。
 */
public record SandboxResult(
        boolean success,
        String stdout,
        String stderr,
        int exitCode,
        long elapsedMs,
        boolean truncated) {
}
