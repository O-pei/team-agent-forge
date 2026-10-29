package com.agentforge.datasource.sandbox;

import com.agentforge.datasource.spi.SandboxProvider;
import com.agentforge.datasource.spi.SandboxRequest;
import com.agentforge.datasource.spi.SandboxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 本地进程沙箱（默认实现，无需 Docker）。
 *
 * <p>安全限制：</p>
 * <ul>
 *   <li>支持语言白名单：java / python / node / shell，其他语言直接拒绝；</li>
 *   <li>代码长度上限 100KB，空代码拒绝；</li>
 *   <li>每次执行在独立临时目录 {@code .data/sandbox/<uuid>}，执行完递归删除；</li>
 *   <li>子进程环境变量使用白名单重建（PATH/JAVA_HOME/SystemRoot/TEMP/TMP/LANG），
 *       不继承父进程环境，避免 API Key 等敏感变量泄漏；请求 env 仅允许 LANG/LC_ALL/TZ/PYTHONIOENCODING；</li>
 *   <li>超时（默认 10s，范围 1~30s）强制 kill 整个进程树，exitCode=-1 且 success=false；</li>
 *   <li>stdout/stderr 各自最多保留 64KB，超出截断并标记 truncated。</li>
 * </ul>
 */
@Slf4j
@Component
public class ProcessSandboxProvider implements SandboxProvider, AvailabilityAware {

    public static final int MAX_OUTPUT_BYTES = 64 * 1024;
    public static final int MAX_CODE_LENGTH = 100_000;
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of("java", "python", "node", "shell");
    public static final Set<String> ALLOWED_ENV_KEYS = Set.of("LANG", "LC_ALL", "TZ", "PYTHONIOENCODING");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final Path baseDir;
    private final Duration defaultTimeout;
    private final boolean enabled;

    public ProcessSandboxProvider(@Value("${agentforge.sandbox.dir:./.data/sandbox}") String sandboxDir,
                                  @Value("${agentforge.sandbox.timeout:10s}") Duration defaultTimeout,
                                  @Value("${agentforge.sandbox.enabled:true}") boolean enabled) {
        this.baseDir = Paths.get(sandboxDir).toAbsolutePath().normalize();
        this.defaultTimeout = defaultTimeout == null ? DEFAULT_TIMEOUT : defaultTimeout;
        this.enabled = enabled;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.warn("无法创建沙箱目录 dir={} error={}", baseDir, e.getMessage());
        }
    }

    @Override
    public String name() {
        return "process";
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
        if (!enabled) {
            return failed("沙箱已禁用");
        }
        if (request == null) {
            return failed("沙箱请求不能为空");
        }
        String language = request.language() == null ? "" : request.language().strip().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            return failed("不支持的语言: " + request.language() + "（可选 " + SUPPORTED_LANGUAGES + "）");
        }
        String code = request.code();
        if (code == null || code.isBlank()) {
            return failed("代码不能为空");
        }
        if (code.length() > MAX_CODE_LENGTH) {
            return failed("代码过长（上限 " + MAX_CODE_LENGTH + " 字符）");
        }
        Duration timeout = normalizeTimeout(request.timeout());
        long startNanos = System.nanoTime();
        Path workDir = baseDir.resolve(UUID.randomUUID().toString());
        Process process = null;
        try {
            Files.createDirectories(workDir);
            List<String> command = buildCommand(language, code, workDir);
            ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
            Map<String, String> environment = builder.environment();
            environment.clear();
            environment.putAll(baseEnvironment());
            applyRequestEnv(environment, request.env());
            process = builder.start();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            AtomicBoolean stdoutTruncated = new AtomicBoolean(false);
            AtomicBoolean stderrTruncated = new AtomicBoolean(false);
            Thread stdoutPump = pump(process.getInputStream(), stdout, stdoutTruncated);
            Thread stderrPump = pump(process.getErrorStream(), stderr, stderrTruncated);

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                killTree(process);
                process.waitFor(2, TimeUnit.SECONDS);
            }
            stdoutPump.join(2000);
            stderrPump.join(2000);

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            String out = stdout.toString(StandardCharsets.UTF_8);
            String err = stderr.toString(StandardCharsets.UTF_8);
            int exitCode = finished ? process.exitValue() : -1;
            if (!finished) {
                err = (err + "\n[超时] 执行超过 " + timeout.toSeconds() + "s，进程已被强制终止").strip();
            }
            boolean success = finished && exitCode == 0;
            log.info("沙箱执行完成 language={} success={} exitCode={} elapsedMs={}", language, success, exitCode, elapsedMs);
            return new SandboxResult(success, out, err, exitCode, elapsedMs,
                    stdoutTruncated.get() || stderrTruncated.get());
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("沙箱执行异常 language={} error={}", language, e.getMessage());
            return new SandboxResult(false, "", "沙箱执行异常: " + e.getMessage(), -1, elapsedMs, false);
        } finally {
            cleanup(workDir, process);
        }
    }

    @Override
    public boolean available() {
        return enabled && javaExecutable() != null;
    }

    @Override
    public List<String> supportedLanguages() {
        List<String> languages = new ArrayList<>();
        if (javaExecutable() != null) {
            languages.add("java");
        }
        if (pythonExecutable() != null) {
            languages.add("python");
        }
        if (nodeExecutable() != null) {
            languages.add("node");
        }
        languages.add("shell");
        return languages;
    }

    @Override
    public String description() {
        return "本地进程沙箱：独立临时目录、超时 kill、stdout/stderr 各 64KB 截断、环境变量白名单";
    }

    // ---------------------------------------------------------------- commands

    private List<String> buildCommand(String language, String code, Path workDir) throws IOException {
        return switch (language) {
            case "java" -> {
                String java = javaExecutable();
                if (java == null) {
                    throw new IllegalStateException("未找到 java 可执行文件（请设置 JAVA_HOME）");
                }
                String fileName = detectPublicClassName(code) + ".java";
                Files.writeString(workDir.resolve(fileName), code, StandardCharsets.UTF_8);
                yield List.of(java, "-Dfile.encoding=UTF-8", fileName);
            }
            case "python" -> {
                String python = pythonExecutable();
                if (python == null) {
                    throw new IllegalStateException("未找到 python 可执行文件（可设置 AGENTFORGE_PYTHON）");
                }
                Files.writeString(workDir.resolve("main.py"), code, StandardCharsets.UTF_8);
                yield List.of(python, "main.py");
            }
            case "node" -> {
                String node = nodeExecutable();
                if (node == null) {
                    throw new IllegalStateException("未找到 node 可执行文件（可设置 AGENTFORGE_NODE）");
                }
                Files.writeString(workDir.resolve("main.js"), code, StandardCharsets.UTF_8);
                yield List.of(node, "main.js");
            }
            default -> shellCommand(workDir, code);
        };
    }

    private List<String> shellCommand(Path workDir, String code) throws IOException {
        if (WINDOWS) {
            Files.writeString(workDir.resolve("main.ps1"), code, StandardCharsets.UTF_8);
            String powershell = findExecutable("powershell");
            if (powershell == null) {
                powershell = findExecutable("pwsh");
            }
            if (powershell == null) {
                throw new IllegalStateException("未找到 powershell 可执行文件");
            }
            return List.of(powershell, "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", "main.ps1");
        }
        Files.writeString(workDir.resolve("main.sh"), code, StandardCharsets.UTF_8);
        return List.of("/bin/sh", "main.sh");
    }

    /** 单文件源码启动要求文件名与 public class 名一致，尽量识别用户类名。 */
    private static String detectPublicClassName(String code) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("public\\s+(?:final\\s+|abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)")
                .matcher(code);
        return matcher.find() ? matcher.group(1) : "Main";
    }

    private static Duration normalizeTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return DEFAULT_TIMEOUT;
        }
        if (timeout.compareTo(MAX_TIMEOUT) > 0) {
            return MAX_TIMEOUT;
        }
        return timeout;
    }

    // ---------------------------------------------------------------- env

    private static Map<String, String> baseEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        copyIfPresent(environment, "PATH");
        copyIfPresent(environment, "JAVA_HOME");
        copyIfPresent(environment, "SYSTEMROOT");
        copyIfPresent(environment, "SYSTEMDRIVE");
        copyIfPresent(environment, "TEMP");
        copyIfPresent(environment, "TMP");
        copyIfPresent(environment, "LANG");
        return environment;
    }

    private static void copyIfPresent(Map<String, String> environment, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            environment.put(key, value);
        }
    }

    private static void applyRequestEnv(Map<String, String> environment, Map<String, String> requestEnv) {
        if (requestEnv == null) {
            return;
        }
        requestEnv.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String normalizedKey = key.strip().toUpperCase(Locale.ROOT);
            if (!ALLOWED_ENV_KEYS.contains(normalizedKey)) {
                return;
            }
            if (value.length() > 1024 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
                return;
            }
            environment.put(normalizedKey, value);
        });
    }

    // ---------------------------------------------------------------- executables

    private static String javaExecutable() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) {
            javaHome = System.getProperty("java.home");
        }
        if (javaHome != null && !javaHome.isBlank()) {
            Path candidate = Paths.get(javaHome, "bin", WINDOWS ? "java.exe" : "java");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return findExecutable("java");
    }

    private static String pythonExecutable() {
        String configured = System.getenv("AGENTFORGE_PYTHON");
        if (configured != null && Files.isRegularFile(Paths.get(configured))) {
            return configured;
        }
        String found = findExecutable("python");
        if (found == null) {
            found = findExecutable("python3");
        }
        if (found == null && WINDOWS) {
            Path known = Paths.get("C:\\dev_env\\miniconda\\python.exe");
            if (Files.isRegularFile(known)) {
                return known.toString();
            }
        }
        return found;
    }

    private static String nodeExecutable() {
        String configured = System.getenv("AGENTFORGE_NODE");
        if (configured != null && Files.isRegularFile(Paths.get(configured))) {
            return configured;
        }
        String found = findExecutable("node");
        if (found == null && WINDOWS) {
            Path known = Paths.get("C:\\dev_env\\Node\\node-24.14.0\\node.exe");
            if (Files.isRegularFile(known)) {
                return known.toString();
            }
        }
        return found;
    }

    private static String findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        String[] candidates = WINDOWS
                ? new String[]{name + ".exe", name + ".cmd", name + ".bat"}
                : new String[]{name};
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String candidate : candidates) {
                Path file = Paths.get(dir, candidate);
                if (Files.isRegularFile(file)) {
                    return file.toAbsolutePath().toString();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- io helpers

    private static Thread pump(InputStream input, ByteArrayOutputStream sink, AtomicBoolean truncated) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            int total = 0;
            try (InputStream in = input) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (total < MAX_OUTPUT_BYTES) {
                        int allowed = Math.min(read, MAX_OUTPUT_BYTES - total);
                        sink.write(buffer, 0, allowed);
                        total += allowed;
                        if (allowed < read) {
                            truncated.set(true);
                        }
                    } else {
                        truncated.set(true);
                    }
                }
            } catch (IOException ignored) {
                // 进程被 kill 时流关闭属正常情况
            }
        }, "sandbox-output-pump");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void killTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception ignored) {
            // ignore
        }
        process.destroyForcibly();
    }

    private void cleanup(Path workDir, Process process) {
        try {
            if (process != null && process.isAlive()) {
                killTree(process);
            }
        } catch (Exception ignored) {
            // ignore
        }
        try {
            if (!workDir.normalize().startsWith(baseDir)) {
                return;
            }
            if (!Files.exists(workDir)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(workDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // ignore
                    }
                });
            }
        } catch (IOException e) {
            log.warn("清理沙箱目录失败 dir={} error={}", workDir, e.getMessage());
        }
    }

    private static SandboxResult failed(String message) {
        return new SandboxResult(false, "", message, -1, 0L, false);
    }
}
