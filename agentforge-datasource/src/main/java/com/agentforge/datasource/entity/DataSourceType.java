package com.agentforge.datasource.entity;

import java.util.Locale;

/**
 * 支持的数据源类型：JDBC URL 前缀校验 + 驱动类。
 */
public enum DataSourceType {

    MYSQL("jdbc:mysql:", "com.mysql.cj.jdbc.Driver"),
    POSTGRESQL("jdbc:postgresql:", "org.postgresql.Driver"),
    H2("jdbc:h2:", "org.h2.Driver");

    private final String urlPrefix;
    private final String driverClassName;

    DataSourceType(String urlPrefix, String driverClassName) {
        this.urlPrefix = urlPrefix;
        this.driverClassName = driverClassName;
    }

    public String urlPrefix() {
        return urlPrefix;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public boolean matches(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.toLowerCase(Locale.ROOT).startsWith(urlPrefix);
    }

    /**
     * 解析类型名，非法抛 IllegalArgumentException。
     */
    public static DataSourceType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("数据源类型不能为空");
        }
        try {
            return DataSourceType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的数据源类型: " + value + "（可选 MYSQL/POSTGRESQL/H2）");
        }
    }
}
