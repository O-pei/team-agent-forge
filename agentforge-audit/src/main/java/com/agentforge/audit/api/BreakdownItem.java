package com.agentforge.audit.api;

import java.math.BigDecimal;

/**
 * 用量分解（按 model / agent / user 聚合）。
 */
public record BreakdownItem(
        String key,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long requests,
        BigDecimal cost) {
}
