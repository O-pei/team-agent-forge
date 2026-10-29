package com.agentforge.audit.usage;

/**
 * Token 用量上报入口。实现必须满足：
 * <ul>
 *   <li>异步/批量落库，不阻塞模型调用线程；</li>
 *   <li>异常仅记录日志，绝不影响主流程；</li>
 *   <li>流式调用由调用方累加后一次上报。</li>
 * </ul>
 */
public interface TokenUsageService {

    void record(TokenUsageEvent event);
}
