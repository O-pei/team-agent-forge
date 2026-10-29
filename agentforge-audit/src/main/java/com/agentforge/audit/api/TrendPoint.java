package com.agentforge.audit.api;

import java.math.BigDecimal;

/**
 * 用量趋势点（按天/小时聚合）。
 */
public record TrendPoint(
        String time,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long requests,
        BigDecimal cost) {
}
