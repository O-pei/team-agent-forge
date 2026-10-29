package com.agentforge.datasource.service;

import com.agentforge.common.crypto.CryptoUtils;
import com.agentforge.datasource.entity.DataSourceEntity;
import com.agentforge.datasource.entity.DataSourceType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源连接池管理：每个数据源一个 Hikari 池，懒创建、更新/删除时关闭。
 */
@Slf4j
@Component
public class DataSourceConnectionManager {

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    /**
     * 获取数据源连接（自动建池）。
     */
    public Connection getConnection(DataSourceEntity entity) throws SQLException {
        if (entity == null || entity.getId() == null) {
            throw new SQLException("数据源不存在");
        }
        HikariDataSource dataSource = pools.computeIfAbsent(entity.getId(), id -> buildPool(entity));
        return dataSource.getConnection();
    }

    /**
     * 关闭并移除指定数据源的连接池。
     */
    public void evict(long datasourceId) {
        HikariDataSource dataSource = pools.remove(datasourceId);
        if (dataSource != null) {
            dataSource.close();
            log.info("数据源连接池已关闭 datasourceId={}", datasourceId);
        }
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    private HikariDataSource buildPool(DataSourceEntity entity) {
        DataSourceType type = DataSourceType.parse(entity.getType());
        HikariConfig config = new HikariConfig();
        config.setPoolName("af-ds-" + entity.getId());
        config.setDriverClassName(type.driverClassName());
        config.setJdbcUrl(entity.getJdbcUrl());
        config.setUsername(entity.getUsername());
        config.setPassword(decryptPassword(entity.getPasswordEncrypted()));
        config.setMaximumPoolSize(normalizePoolSize(entity.getMaxPoolSize()));
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        config.setIdleTimeout(60_000);
        config.setMaxLifetime(600_000);
        config.setReadOnly(true);
        // 延迟到首次 getConnection 才真正连库，避免数据源暂时不可用导致启动/请求失败
        config.setInitializationFailTimeout(-1);
        log.info("创建数据源连接池 datasourceId={} type={}", entity.getId(), type);
        return new HikariDataSource(config);
    }

    private static int normalizePoolSize(Integer maxPoolSize) {
        if (maxPoolSize == null || maxPoolSize <= 0) {
            return 5;
        }
        return Math.min(maxPoolSize, 20);
    }

    private static String decryptPassword(String encrypted) {
        try {
            return CryptoUtils.decrypt(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("数据源密码解密失败（密钥可能已更换）");
        }
    }
}
