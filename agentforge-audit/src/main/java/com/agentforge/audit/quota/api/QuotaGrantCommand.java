package com.agentforge.audit.quota.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 管理员手动分配 Token 入参。
 */
public record QuotaGrantCommand(
        @NotNull(message = "分配数量不能为空") @Positive(message = "分配数量必须大于 0") Long amount,
        @Size(max = 200, message = "分配原因最长 200 字符") String reason) {
}
