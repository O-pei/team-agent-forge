package com.agentforge.audit.usage;

/**
 * Token 用量上报入口：实现须异步落库、不阻塞模型调用线程；异常只记日志，不影响主流程；流式调用由调用方累加后一次上报。
 */
public interface TokenUsageService {

    void record(TokenUsageEvent event);
}
