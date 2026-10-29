package com.agentforge.datasource.sandbox;

import com.agentforge.datasource.spi.SandboxRequest;
import com.agentforge.datasource.spi.SandboxResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程沙箱单测：java 执行成功、超时 kill、非法语言/超大代码拒绝、env 白名单不泄漏父进程密钥。
 */
class ProcessSandboxProviderTest {

    @TempDir
    Path sandboxDir;

    private ProcessSandboxProvider provider(Duration timeout) {
        return new ProcessSandboxProvider(sandboxDir.toString(), timeout, true);
    }

    @Test
    @DisplayName("执行 Java 打印 hi 成功")
    void executesJava() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """;
        SandboxResult result = provider(Duration.ofSeconds(20))
                .execute(new SandboxRequest("java", code, Duration.ofSeconds(20), null));
        assertTrue(result.success(), "stderr=" + result.stderr());
        assertTrue(result.stdout().contains("hi"), "stdout=" + result.stdout());
        assertTrue(result.exitCode() == 0);
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("死循环超时被 kill 且 success=false")
    void timeoutKillsProcess() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        while (true) { }
                    }
                }
                """;
        long start = System.currentTimeMillis();
        SandboxResult result = provider(Duration.ofSeconds(2))
                .execute(new SandboxRequest("java", code, Duration.ofSeconds(1), null));
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(result.success());
        assertTrue(result.exitCode() == -1, "超时 exitCode 应为 -1: " + result.exitCode());
        assertTrue(result.stderr().contains("超时"), "stderr=" + result.stderr());
        assertTrue(elapsed < 20_000, "超时 kill 不应耗时过久: " + elapsed);
    }

    @Test
    @DisplayName("非法语言直接拒绝")
    void rejectsUnsupportedLanguage() {
        SandboxResult result = provider(Duration.ofSeconds(5))
                .execute(new SandboxRequest("ruby", "puts 1", Duration.ofSeconds(5), null));
        assertFalse(result.success());
        assertTrue(result.stderr().contains("不支持的语言"));
    }

    @Test
    @DisplayName("超大代码/空代码拒绝")
    void rejectsOversizedOrEmptyCode() {
        ProcessSandboxProvider provider = provider(Duration.ofSeconds(5));
        SandboxResult huge = provider.execute(new SandboxRequest("java", "x".repeat(100_001), Duration.ofSeconds(5), null));
        assertFalse(huge.success());
        assertTrue(huge.stderr().contains("代码过长"));
        SandboxResult empty = provider.execute(new SandboxRequest("java", "   ", Duration.ofSeconds(5), null));
        assertFalse(empty.success());
        assertTrue(empty.stderr().contains("代码不能为空"));
    }

    @Test
    @DisplayName("env 白名单：父进程敏感变量不泄漏，非法 key 被忽略")
    void envAllowlist() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        String secret = System.getenv("AGENTFORGE_API_KEY");
                        System.out.println(secret == null ? "no-secret" : secret);
                        System.out.println(System.getenv().getOrDefault("LANG", "no-lang"));
                    }
                }
                """;
        SandboxResult result = provider(Duration.ofSeconds(20))
                .execute(new SandboxRequest("java", code, Duration.ofSeconds(20),
                        Map.of("LANG", "zh_CN.UTF-8", "AGENTFORGE_API_KEY", "sk-should-not-leak")));
        assertTrue(result.success(), "stderr=" + result.stderr());
        assertTrue(result.stdout().contains("no-secret"), "stdout=" + result.stdout());
        assertFalse(result.stdout().contains("sk-should-not-leak"));
        assertTrue(result.stdout().contains("zh_CN.UTF-8"));
    }

    @Test
    @DisplayName("支持语言列表与可用性")
    void languagesAndAvailability() {
        ProcessSandboxProvider provider = provider(Duration.ofSeconds(5));
        assertTrue(provider.available());
        assertTrue(provider.supportedLanguages().contains("java"));
        assertTrue(provider.name().equals("process"));
    }
}
