package com.agentforge.datasource.sandbox;

import com.agentforge.datasource.spi.SandboxProvider;
import com.agentforge.datasource.spi.SandboxRequest;
import com.agentforge.datasource.spi.SandboxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Docker 沙箱（可选实现）：仅当 {@code agentforge.sandbox.docker-enabled=true} 且本机
 * 检测到可用的 Docker daemon 时可用，否则自动跳过（不阻塞启动）。
 *
 * <p>本机无 Docker，实际运行走 {@link ProcessSandboxProvider}。</p>
 */
@Slf4j
@Component
public class DockerSandboxProvider implements SandboxProvider, AvailabilityAware {

    private static final List<String> LANGUAGES = List.of("java", "python", "node", "shell");
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private final boolean enabled;
    private final Path baseDir;
    private volatile Boolean availableCache;

    public DockerSandboxProvider(@Value("${agentforge.sandbox.docker-enabled:false}") boolean enabled,
                                 @Value("${agentforge.sandbox.dir:./.data/sandbox}") String sandboxDir) {
        this.enabled = enabled;
        this.baseDir = Paths.get(sandboxDir).toAbsolutePath().normalize();
        if (enabled) {
            boolean ok = probeDocker();
            availableCache = ok;
            log.info("Docker 沙箱探测: enabled=true available={}", ok);
        } else {
            log.info("Docker 沙箱未启用（agentforge.sandbox.docker-enabled=false），使用 Process 沙箱");
        }
    }

    @Override
    public String name() {
        return "docker";
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
        if (!available()) {
            return new SandboxResult(false, "", "Docker 不可用（未安装、未启动或未启用），请使用 process 沙箱",
                    -1, 0L, false);
        }
        if (request == null || request.code() == null || request.code().isBlank()) {
            return new SandboxResult(false, "", "代码不能为空", -1, 0L, false);
        }
        String language = request.language() == null ? "" : request.language().strip().toLowerCase(Locale.ROOT);
        String image;
        String fileName;
        List<String> runCommand;
        switch (language) {
            case "java" -> {
                image = "eclipse-temurin:21-jdk";
                fileName = "Main.java";
                runCommand = List.of("java", fileName);
            }
            case "python" -> {
                image = "python:3.12-slim";
                fileName = "main.py";
                runCommand = List.of("python", "main.py");
            }
            case "node" -> {
                image = "node:22-alpine";
                fileName = "main.js";
                runCommand = List.of("node", "main.js");
            }
            case "shell" -> {
                image = "alpine:3.20";
                fileName = "main.sh";
                runCommand = List.of("sh", "main.sh");
            }
            default -> {
                return new SandboxResult(false, "", "不支持的语言: " + request.language(), -1, 0L, false);
            }
        }
        long startNanos = System.nanoTime();
        Path workDir = baseDir.resolve("docker-" + UUID.randomUUID());
        Process process = null;
        try {
            Files.createDirectories(workDir);
            Files.writeString(workDir.resolve(fileName), request.code(), StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(List.of("docker", "run", "--rm", "--network", "none",
                    "--memory", "256m", "--cpus", "0.5",
                    "-v", workDir.toAbsolutePath() + ":/work", "-w", "/work", image));
            command.addAll(runCommand);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            process = builder.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            AtomicBoolean truncated = new AtomicBoolean(false);
            Thread outPump = pump(process.getInputStream(), stdout, truncated);
            Thread errPump = pump(process.getErrorStream(), stderr, truncated);
            Duration timeout = request.timeout() == null ? Duration.ofSeconds(10) : request.timeout();
            boolean finished = process.waitFor(Math.min(timeout.toMillis(), 30_000), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            outPump.join(2000);
            errPump.join(2000);
            int exitCode = finished ? process.exitValue() : -1;
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            return new SandboxResult(finished && exitCode == 0,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8),
                    exitCode, elapsedMs, truncated.get());
        } catch (Exception e) {
            log.warn("Docker 沙箱执行失败 error={}", e.getMessage());
            return new SandboxResult(false, "", "Docker 沙箱执行失败: " + e.getMessage(),
                    -1, (System.nanoTime() - startNanos) / 1_000_000, false);
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Override
    public boolean available() {
        if (!enabled) {
            return false;
        }
        if (availableCache == null) {
            availableCache = probeDocker();
        }
        return availableCache;
    }

    @Override
    public List<String> supportedLanguages() {
        return LANGUAGES;
    }

    @Override
    public String description() {
        return "Docker 沙箱：--network none、内存 256MB、CPU 0.5；需 agentforge.sandbox.docker-enabled=true 且 Docker 可用";
    }

    private static boolean probeDocker() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

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
            } catch (Exception ignored) {
                // ignore
            }
        }, "docker-sandbox-pump");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // ignore
                    }
                });
            }
        } catch (Exception ignored) {
            // ignore
        }
    }
}
