package com.agentforge.datasource.service;

import com.agentforge.common.api.PageResult;
import com.agentforge.common.crypto.CryptoUtils;
import com.agentforge.common.exception.BizException;
import com.agentforge.datasource.dto.DataSourceRequest;
import com.agentforge.datasource.dto.DataSourceResponse;
import com.agentforge.datasource.dto.QueryLogResponse;
import com.agentforge.datasource.dto.SchemaResponse;
import com.agentforge.datasource.dto.TestConnectionResult;
import com.agentforge.datasource.entity.DataSourceEntity;
import com.agentforge.datasource.entity.DataSourceType;
import com.agentforge.datasource.entity.QueryLogEntity;
import com.agentforge.datasource.repository.DataSourceRepository;
import com.agentforge.datasource.repository.QueryLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据源 CRUD、连接测试、结构浏览、查询审计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceService {

    private static final int MAX_TABLES = 100;

    private final DataSourceRepository dataSourceRepository;
    private final QueryLogRepository queryLogRepository;
    private final DataSourceConnectionManager connectionManager;

    @Transactional(readOnly = true)
    public PageResult<DataSourceResponse> list(int page, int size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedSize, Sort.by(Sort.Direction.DESC, "id"));
        Page<DataSourceEntity> result = dataSourceRepository.findAll(pageable);
        return PageResult.of(result.getContent().stream().map(DataSourceResponse::from).toList(),
                result.getTotalElements(), normalizedPage, normalizedSize);
    }

    @Transactional(readOnly = true)
    public DataSourceResponse get(long id) {
        return DataSourceResponse.from(require(id));
    }

    @Transactional
    public DataSourceResponse create(DataSourceRequest request) {
        DataSourceType type = parseType(request.type());
        validateJdbcUrl(type, request.jdbcUrl());
        DataSourceEntity entity = new DataSourceEntity();
        entity.setName(request.name().strip());
        entity.setType(type.name());
        entity.setJdbcUrl(request.jdbcUrl().strip());
        entity.setUsername(request.username().strip());
        entity.setPasswordEncrypted(encryptPassword(request.password()));
        entity.setReadOnly(request.readOnly() == null || request.readOnly());
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setMaxPoolSize(request.maxPoolSize() == null ? 5 : request.maxPoolSize());
        DataSourceEntity saved = dataSourceRepository.save(entity);
        log.info("数据源已创建 id={} name={} type={}", saved.getId(), saved.getName(), saved.getType());
        return DataSourceResponse.from(saved);
    }

    @Transactional
    public DataSourceResponse update(long id, DataSourceRequest request) {
        DataSourceEntity entity = require(id);
        DataSourceType type = parseType(request.type());
        validateJdbcUrl(type, request.jdbcUrl());
        entity.setName(request.name().strip());
        entity.setType(type.name());
        entity.setJdbcUrl(request.jdbcUrl().strip());
        entity.setUsername(request.username().strip());
        if (request.password() != null && !request.password().isBlank()) {
            entity.setPasswordEncrypted(encryptPassword(request.password()));
        }
        if (request.readOnly() != null) {
            entity.setReadOnly(request.readOnly());
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        if (request.maxPoolSize() != null) {
            entity.setMaxPoolSize(request.maxPoolSize());
        }
        connectionManager.evict(id);
        DataSourceEntity saved = dataSourceRepository.save(entity);
        log.info("数据源已更新 id={} name={}", saved.getId(), saved.getName());
        return DataSourceResponse.from(saved);
    }

    @Transactional
    public void delete(long id) {
        DataSourceEntity entity = require(id);
        connectionManager.evict(id);
        dataSourceRepository.delete(entity);
        log.info("数据源已删除 id={} name={}", id, entity.getName());
    }

    /**
     * 连接测试：DriverManager 直连 + SELECT 1，5 秒登录超时。
     */
    public TestConnectionResult test(long id) {
        DataSourceEntity entity = require(id);
        long start = System.currentTimeMillis();
        DataSourceType type;
        try {
            type = DataSourceType.parse(entity.getType());
        } catch (IllegalArgumentException e) {
            return new TestConnectionResult(false, 0L, e.getMessage());
        }
        try {
            Class.forName(type.driverClassName());
        } catch (ClassNotFoundException e) {
            return new TestConnectionResult(false, System.currentTimeMillis() - start,
                    "驱动未安装: " + type.driverClassName());
        }
        String password;
        try {
            password = CryptoUtils.decrypt(entity.getPasswordEncrypted());
        } catch (Exception e) {
            return new TestConnectionResult(false, System.currentTimeMillis() - start, "密码解密失败");
        }
        try {
            DriverManager.setLoginTimeout(5);
            try (Connection connection = DriverManager.getConnection(entity.getJdbcUrl(), entity.getUsername(), password);
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("SELECT 1");
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("数据源连接测试成功 id={} latencyMs={}", id, elapsed);
            return new TestConnectionResult(true, elapsed, "连接成功");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("数据源连接测试失败 id={} error={}", id, e.getMessage());
            return new TestConnectionResult(false, elapsed, "连接失败: " + sanitize(e.getMessage()));
        }
    }

    /**
     * 结构浏览：当前 catalog/schema 下的表、字段、注释、主键。
     */
    public SchemaResponse schema(long id) {
        DataSourceEntity entity = require(id);
        try (Connection connection = connectionManager.getConnection(entity)) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = safeCatalog(connection);
            String schema = safeSchema(connection);
            List<SchemaResponse.TableSchema> tables = new ArrayList<>();
            try (ResultSet tableResult = metaData.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                while (tableResult.next() && tables.size() < MAX_TABLES) {
                    String tableName = tableResult.getString("TABLE_NAME");
                    String tableComment = tableResult.getString("REMARKS");
                    tables.add(new SchemaResponse.TableSchema(tableName, tableComment,
                            columnsOf(metaData, catalog, schema, tableName)));
                }
            }
            return new SchemaResponse(tables);
        } catch (SQLException e) {
            throw new BizException(com.agentforge.common.api.ErrorCode.SERVICE_UNAVAILABLE,
                    "读取数据源结构失败: " + sanitize(e.getMessage()));
        }
    }

    @Transactional(readOnly = true)
    public PageResult<QueryLogResponse> queryLogs(long datasourceId, int page, int size) {
        require(datasourceId);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedSize, Sort.by(Sort.Direction.DESC, "id"));
        Page<QueryLogEntity> result = queryLogRepository.findByDatasourceId(datasourceId, pageable);
        return PageResult.of(result.getContent().stream().map(QueryLogResponse::from).toList(),
                result.getTotalElements(), normalizedPage, normalizedSize);
    }

    public DataSourceEntity require(long id) {
        return dataSourceRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("数据源不存在: " + id));
    }

    // ---------------------------------------------------------------- helpers

    private List<SchemaResponse.ColumnSchema> columnsOf(DatabaseMetaData metaData, String catalog,
                                                        String schema, String table) throws SQLException {
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet pkResult = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (pkResult.next()) {
                primaryKeys.add(pkResult.getString("COLUMN_NAME"));
            }
        }
        List<SchemaResponse.ColumnSchema> columns = new ArrayList<>();
        try (ResultSet columnResult = metaData.getColumns(catalog, schema, table, "%")) {
            while (columnResult.next()) {
                String columnName = columnResult.getString("COLUMN_NAME");
                String typeName = columnResult.getString("TYPE_NAME");
                int nullableFlag = columnResult.getInt("NULLABLE");
                String comment = columnResult.getString("REMARKS");
                columns.add(new SchemaResponse.ColumnSchema(columnName, typeName,
                        nullableFlag != DatabaseMetaData.columnNoNulls,
                        primaryKeys.contains(columnName), comment));
            }
        }
        return columns;
    }

    private static String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    private static String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (Exception e) {
            return null;
        }
    }

    private static DataSourceType parseType(String type) {
        try {
            return DataSourceType.parse(type);
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest(e.getMessage());
        }
    }

    private static void validateJdbcUrl(DataSourceType type, String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.strip();
        if (!type.matches(url)) {
            throw BizException.badRequest("JDBC URL 与数据源类型不匹配，应以 " + type.urlPrefix() + " 开头");
        }
    }

    private static String encryptPassword(String password) {
        if (password == null) {
            return "";
        }
        return CryptoUtils.encrypt(password);
    }

    /** 清洗异常信息，避免把 URL 中的密码等敏感信息返回给前端。 */
    static String sanitize(String message) {
        if (message == null) {
            return "未知错误";
        }
        String sanitized = message.replaceAll("(?i)(password|pwd)=[^&\\s;]+", "$1=***");
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    private static int normalizePage(int page) {
        return page < 1 ? 1 : page;
    }

    private static int normalizeSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }

    /** 仅供查询服务复用的日志写入（失败不影响主流程）。 */
    @Transactional
    public void recordQueryLog(long datasourceId, String sql, boolean success, int rowCount,
                               long elapsedMs, String errorMessage) {
        try {
            QueryLogEntity log = new QueryLogEntity();
            log.setDatasourceId(datasourceId);
            log.setSqlText(sql == null ? "" : (sql.length() <= 4000 ? sql : sql.substring(0, 4000)));
            log.setSuccess(success);
            log.setRowCount(rowCount);
            log.setElapsedMs(elapsedMs);
            log.setErrorMessage(errorMessage == null ? null
                    : (errorMessage.length() <= 1000 ? errorMessage : errorMessage.substring(0, 1000)));
            queryLogRepository.save(log);
        } catch (Exception e) {
            log.warn("写查询日志失败 datasourceId={} error={}", datasourceId, e.getMessage());
        }
    }
}
