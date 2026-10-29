package com.agentforge.agent.tool;

import com.agentforge.datasource.spi.SqlQueryResult;
import com.agentforge.datasource.spi.SqlQueryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读 SQL 查询工具：SQL 校验、强制 LIMIT、超时与审计由 SqlQueryService 实现负责。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlQueryTool implements AgentToolDefinition {

    private final ObjectProvider<SqlQueryService> queryServiceProvider;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return ToolNames.SQL_QUERY;
    }

    @Override
    public String description() {
        return "对已配置的数据源执行只读 SELECT 查询";
    }

    @Override
    public String riskLevel() {
        return "HIGH";
    }

    @Override
    public Object toolBean() {
        return this;
    }

    @Tool(name = ToolNames.SQL_QUERY, description = "对已配置的数据源执行单条只读 SELECT 查询，返回列与数据行")
    public String sqlQuery(
            @ToolParam(description = "数据源 ID") Long datasourceId,
            @ToolParam(description = "单条 SELECT 语句，禁止任何写操作") String sql,
            @ToolParam(description = "最大返回行数，默认 100，最大 500", required = false) Integer maxRows) {
        if (datasourceId == null) {
            return "查询失败: 必须提供 datasourceId";
        }
        if (sql == null || sql.isBlank()) {
            return "查询失败: SQL 不能为空";
        }
        SqlQueryService service = queryServiceProvider.getIfAvailable();
        if (service == null) {
            return "SQL 查询服务尚未配置（数据源模块未就绪），无法执行查询。";
        }
        int limit = maxRows == null || maxRows <= 0 ? 100 : Math.min(maxRows, 500);
        try {
            SqlQueryResult result = service.query(datasourceId, sql, limit);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("columns", result.columns());
            payload.put("rows", result.rows());
            payload.put("rowCount", result.rowCount());
            payload.put("elapsedMs", result.elapsedMs());
            payload.put("truncated", result.truncated());
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "查询结果序列化失败: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("sql_query 调用失败: {}", e.getMessage());
            return "SQL 查询失败: " + e.getMessage();
        }
    }
}
