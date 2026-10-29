package com.agentforge.audit.quota;

/**
 * Token 额度服务。agent 模块在运行开始前与每次模型调用前调用 assertCanConsume，
 * 管理端通过 quota 包的 controller 维护套餐、用户额度与手动分配。
 */
public interface QuotaService {

    /**
     * 查询用户当前额度快照，包含套餐、当日用量、总额度用量与剩余量。
     */
    QuotaStatus status(long userId);

    /**
     * 校验用户是否还有可用额度；每日额度或总额度任一用尽时抛 QuotaExceededException（42901）。
     */
    void assertCanConsume(long userId);
}
