package com.agentforge.audit.quota.api;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 套餐创建/修改入参：code 仅在创建时生效，修改时忽略；额度 0 表示不限。
 */
public record QuotaTierCommand(
        @Size(max = 32, message = "套餐编码最长 32 字符") String code,
        @Size(max = 64, message = "套餐名称最长 64 字符") String name,
        @Size(max = 255, message = "套餐描述最长 255 字符") String description,
        @PositiveOrZero(message = "每日额度不能为负数") Long dailyTokenLimit,
        @PositiveOrZero(message = "总额度不能为负数") Long totalTokenLimit,
        Integer sortOrder,
        Boolean enabled) {
}
