package com.agentforge.datasource.spi;

import java.time.Duration;
import java.util.Map;

/**
 * 沙箱执行请求。
 *
 * @param language 语言：java / python / node / shell
 * @param code     待执行代码或脚本
 * @param timeout  超时时间
 * @param env      额外环境变量（仅白名单 key 生效）
 */
public record SandboxRequest(String language, String code, Duration timeout, Map<String, String> env) {
}
