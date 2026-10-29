package com.agentforge.datasource.dto;

import com.agentforge.datasource.entity.DataSourceEntity;

import java.time.LocalDateTime;

/**
 * 数据源响应：密码永不回显，只返回 hasPassword 标记。
 */
public record DataSourceResponse(
        Long id,
        String name,
        String type,
        String jdbcUrl,
        String username,
        boolean hasPassword,
        Boolean readOnly,
        Boolean enabled,
        Integer maxPoolSize,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static DataSourceResponse from(DataSourceEntity entity) {
        return new DataSourceResponse(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getJdbcUrl(),
                entity.getUsername(),
                entity.getPasswordEncrypted() != null && !entity.getPasswordEncrypted().isBlank(),
                entity.getReadOnly(),
                entity.getEnabled(),
                entity.getMaxPoolSize(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
