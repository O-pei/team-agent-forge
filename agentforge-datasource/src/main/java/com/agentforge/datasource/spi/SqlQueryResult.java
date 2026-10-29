package com.agentforge.datasource.spi;

import java.util.List;

/**
 * 只读 SQL 查询结果（已做行数/字节截断）。
 */
public record SqlQueryResult(
        List<String> columns,
        List<List<Object>> rows,
        long elapsedMs,
        boolean truncated) {

    public int rowCount() {
        return rows == null ? 0 : rows.size();
    }
}
