package com.agentforge.audit.quota.api;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * 用户额度修改入参：tierId 为 null 表示继承默认套餐，dailyLimit/totalLimit 为 null 表示继承套餐额度，0 表示不限。
 */
public record UserQuotaCommand(
        Long tierId,
        @PositiveOrZero(message = "每日额度不能为负数") Long dailyLimit,
        @PositiveOrZero(message = "总额度不能为负数") Long totalLimit) {
}
