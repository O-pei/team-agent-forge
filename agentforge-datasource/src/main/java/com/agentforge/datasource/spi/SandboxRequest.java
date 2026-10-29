package com.agentforge.datasource.spi;

import java.time.Duration;
import java.util.Map;

/** 沙箱执行请求：语言（java/python/node/shell）、待执行代码、超时时间与环境变量（env 仅白名单 key 生效）。 */
public record SandboxRequest(String language, String code, Duration timeout, Map<String, String> env) {
}
