package com.agentforge.datasource.dto;

import com.agentforge.datasource.entity.QueryLogEntity;

import java.time.LocalDateTime;

/**
 * 查询审计日志响应。
 */
public record QueryLogResponse(
        Long id,
        Long datasourceId,
        Long userId,
        String sql,
        boolean success,
        Integer rowCount,
        Long elapsedMs,
        String errorMessage,
        LocalDateTime createdAt
) {

    public static QueryLogResponse from(QueryLogEntity log) {
        return new QueryLogResponse(
                log.getId(),
                log.getDatasourceId(),
                log.getUserId(),
                log.getSqlText(),
                Boolean.TRUE.equals(log.getSuccess()),
                log.getRowCount(),
                log.getElapsedMs(),
                log.getErrorMessage(),
                log.getCreatedAt());
    }
}
