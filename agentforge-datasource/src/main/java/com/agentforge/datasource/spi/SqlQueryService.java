package com.agentforge.datasource.spi;

/**
 * 内部已包含：单条 SELECT 校验、强制 LIMIT、查询超时、行数与字节上限、审计记录。
 */
public interface SqlQueryService {

    SqlQueryResult query(Long datasourceId, String sql, int maxRows);
}
