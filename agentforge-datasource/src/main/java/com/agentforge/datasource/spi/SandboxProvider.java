package com.agentforge.datasource.spi;

/**
 * 代码沙箱 SPI。默认 {@code ProcessSandboxProvider}；Docker 可用时启用 Docker 实现。
 */
public interface SandboxProvider {

    SandboxResult execute(SandboxRequest request);

    /** 实现名称，用于前端展示（process / docker）。 */
    String name();
}
