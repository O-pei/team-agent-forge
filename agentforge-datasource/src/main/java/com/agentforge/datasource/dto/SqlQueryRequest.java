package com.agentforge.datasource.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 只读查询请求。
 */
public record SqlQueryRequest(
        @NotBlank(message = "SQL 不能为空")
        @Size(max = 10000, message = "SQL 最长 10000 字符")
        String sql,

        @Min(value = 1, message = "maxRows 最小 1")
        @Max(value = 500, message = "maxRows 最大 500")
        Integer maxRows
) {
}
