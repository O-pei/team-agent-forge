package com.agentforge.audit.quota;

import com.agentforge.audit.quota.api.QuotaGrantView;
import com.agentforge.audit.quota.api.QuotaTierCommand;
import com.agentforge.audit.quota.api.QuotaTierView;
import com.agentforge.audit.quota.api.UserQuotaCommand;
import com.agentforge.audit.quota.api.UserQuotaView;
import com.agentforge.common.api.PageResult;

import java.util.List;

/**
 * Token 额度服务：agent 运行前与每次模型调用前调用 assertCanConsume 预检，
 * 管理端维护套餐、用户额度与手动分配。额度口径为 ai_token_usage.total_tokens 按用户聚合。
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

    List<QuotaTierView> listTiers();

    QuotaTierView createTier(QuotaTierCommand cmd);

    QuotaTierView updateTier(Long id, QuotaTierCommand cmd);

    void deleteTier(Long id);

    PageResult<UserQuotaView> listUsers(String keyword, int page, int size);

    UserQuotaView updateUserQuota(Long userId, UserQuotaCommand cmd);

    void resetUserQuota(Long userId);

    UserQuotaView grantTokens(Long userId, long amount, String reason, Long operatorUserId);

    PageResult<QuotaGrantView> listGrants(Long userId, int page, int size);
}
