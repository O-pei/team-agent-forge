package com.agentforge.datasource.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 数据源创建/更新请求。更新时 password 为空表示保持原密码不变。
 */
public record DataSourceRequest(
        @NotBlank(message = "数据源名称不能为空")
        @Size(max = 128, message = "数据源名称最长 128 字符")
        String name,

        @NotBlank(message = "数据源类型不能为空")
        @Pattern(regexp = "(?i)MYSQL|POSTGRESQL|H2", message = "类型仅支持 MYSQL/POSTGRESQL/H2")
        String type,

        @NotBlank(message = "JDBC URL 不能为空")
        @Size(max = 1024, message = "JDBC URL 最长 1024 字符")
        String jdbcUrl,

        @NotBlank(message = "用户名不能为空")
        @Size(max = 128, message = "用户名最长 128 字符")
        String username,

        @Size(max = 512, message = "密码最长 512 字符")
        String password,

        @Min(value = 1, message = "连接池大小最小 1")
        @Max(value = 20, message = "连接池大小最大 20")
        Integer maxPoolSize,

        Boolean readOnly,

        Boolean enabled
) {
}
