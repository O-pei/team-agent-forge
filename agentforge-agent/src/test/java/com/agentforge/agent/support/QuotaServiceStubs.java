package com.agentforge.agent.support;

import com.agentforge.audit.quota.QuotaService;
import com.agentforge.audit.quota.QuotaStatus;
import com.agentforge.common.exception.QuotaExceededException;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 额度服务测试桩：用动态代理实现，audit 模块扩展管理端方法时测试无需跟着补方法。
 */
public final class QuotaServiceStubs {

    private QuotaServiceStubs() {
    }

    /** 允许前 maxCalls 次调用，之后抛 42901。 */
    public static QuotaService allowCalls(int maxCalls) {
        AtomicInteger used = new AtomicInteger();
        return (QuotaService) Proxy.newProxyInstance(QuotaServiceStubs.class.getClassLoader(),
                new Class<?>[]{QuotaService.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if ("assertCanConsume".equals(name)) {
                        if (used.get() >= maxCalls) {
                            throw new QuotaExceededException("Token 额度已用尽");
                        }
                        used.incrementAndGet();
                        return null;
                    }
                    if ("status".equals(name)) {
                        long userId = args == null ? 0L : ((Number) args[0]).longValue();
                        return new QuotaStatus(userId, "free", "免费版", 0, used.get(),
                                Math.max(0, maxCalls - used.get()), 0, used.get(), 0, 0, false, null);
                    }
                    if ("toString".equals(name)) {
                        return "QuotaServiceStub";
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    return null;
                });
    }

    /** 任何一次调用都超额。 */
    public static QuotaService alwaysExceeded() {
        return allowCalls(0);
    }
}
