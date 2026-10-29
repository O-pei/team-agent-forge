package com.agentforge.datasource.service;

import com.agentforge.common.api.ErrorCode;
import com.agentforge.common.exception.BizException;
import com.agentforge.datasource.entity.DataSourceEntity;
import com.agentforge.datasource.repository.DataSourceRepository;
import com.agentforge.datasource.security.SqlGuard;
import com.agentforge.datasource.spi.SqlQueryResult;
import com.agentforge.datasource.spi.SqlQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读 SQL 查询服务（实现冻结 SPI {@link SqlQueryService}）。
 *
 * <p>执行前依次校验：数据源启用 → 只读标记 → {@link SqlGuard}（单条 SELECT/禁 DDL/DML/注释/加锁/强制 LIMIT）
 * → queryTimeout 10s → setMaxRows 防大结果集 → 写 ds_query_log。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceSqlQueryService implements SqlQueryService {

    private static final int MAX_CELL_LENGTH = 4000;

    private final DataSourceRepository dataSourceRepository;
    private final DataSourceConnectionManager connectionManager;
    private final DataSourceService dataSourceService;

    @Override
    public SqlQueryResult query(Long datasourceId, String sql, int maxRows) {
        if (datasourceId == null) {
            throw BizException.badRequest("数据源 id 不能为空");
        }
        DataSourceEntity entity = dataSourceRepository.findById(datasourceId)
                .orElseThrow(() -> BizException.notFound("数据源不存在: " + datasourceId));
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw BizException.forbidden("数据源已禁用");
        }
        if (!Boolean.TRUE.equals(entity.getReadOnly())) {
            throw BizException.forbidden("数据源未开启只读模式，禁止执行查询");
        }

        SqlGuard.GuardedSql guarded = SqlGuard.validate(sql, maxRows);
        long start = System.currentTimeMillis();
        try (Connection connection = connectionManager.getConnection(entity);
             PreparedStatement statement = connection.prepareStatement(guarded.sql())) {
            statement.setQueryTimeout(SqlGuard.QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(guarded.maxRows() + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                List<String> columns = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    String label = metaData.getColumnLabel(i);
                    columns.add(label == null || label.isBlank() ? metaData.getColumnName(i) : label);
                }
                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (resultSet.next()) {
                    if (rows.size() >= guarded.maxRows()) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(convert(resultSet.getObject(i)));
                    }
                    rows.add(row);
                }
                long elapsed = System.currentTimeMillis() - start;
                dataSourceService.recordQueryLog(datasourceId, guarded.sql(), true, rows.size(), elapsed, null);
                log.info("只读查询成功 datasourceId={} rows={} elapsedMs={} truncated={}",
                        datasourceId, rows.size(), elapsed, truncated);
                return new SqlQueryResult(columns, rows, elapsed, truncated);
            }
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - start;
            String message = DataSourceService.sanitize(e.getMessage());
            dataSourceService.recordQueryLog(datasourceId, guarded.sql(), false, 0, elapsed, message);
            log.warn("只读查询失败 datasourceId={} error={}", datasourceId, message);
            throw new BizException(ErrorCode.BAD_REQUEST, "SQL 执行失败: " + message);
        }
    }

    private static Object convert(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return "[binary " + bytes.length + " bytes]";
        }
        if (value instanceof Blob blob) {
            try {
                return "[blob " + blob.length() + " bytes]";
            } catch (SQLException e) {
                return "[blob]";
            }
        }
        if (value instanceof Clob clob) {
            try {
                long length = clob.length();
                String text = clob.getSubString(1, (int) Math.min(length, MAX_CELL_LENGTH));
                return text.length() <= MAX_CELL_LENGTH ? text : text.substring(0, MAX_CELL_LENGTH) + "…";
            } catch (SQLException e) {
                return "[clob]";
            }
        }
        String text = value.toString();
        return text.length() <= MAX_CELL_LENGTH ? text : text.substring(0, MAX_CELL_LENGTH) + "…";
    }
}
