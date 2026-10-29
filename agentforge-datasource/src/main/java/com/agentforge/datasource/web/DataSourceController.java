package com.agentforge.datasource.web;

import com.agentforge.common.api.ApiResponse;
import com.agentforge.common.api.PageResult;
import com.agentforge.datasource.dto.DataSourceRequest;
import com.agentforge.datasource.dto.DataSourceResponse;
import com.agentforge.datasource.dto.QueryLogResponse;
import com.agentforge.datasource.dto.SchemaResponse;
import com.agentforge.datasource.dto.SqlQueryRequest;
import com.agentforge.datasource.dto.TestConnectionResult;
import com.agentforge.datasource.service.DataSourceService;
import com.agentforge.datasource.service.DatasourceSqlQueryService;
import com.agentforge.datasource.spi.SqlQueryResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源接口（契约 docs/04-API契约.md §4）：密码只写不读，查询强制只读。
 */
@RestController
@RequestMapping("/api/v1/datasources")
@RequiredArgsConstructor
public class DataSourceController {

    private final DataSourceService dataSourceService;
    private final DatasourceSqlQueryService sqlQueryService;

    @GetMapping
    @PreAuthorize("hasAuthority('datasource:read')")
    public ApiResponse<PageResult<DataSourceResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(dataSourceService.list(page, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('datasource:write')")
    public ApiResponse<DataSourceResponse> create(@Valid @RequestBody DataSourceRequest request) {
        return ApiResponse.ok(dataSourceService.create(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('datasource:read')")
    public ApiResponse<DataSourceResponse> get(@PathVariable long id) {
        return ApiResponse.ok(dataSourceService.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('datasource:write')")
    public ApiResponse<DataSourceResponse> update(@PathVariable long id,
                                                  @Valid @RequestBody DataSourceRequest request) {
        return ApiResponse.ok(dataSourceService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('datasource:write')")
    public ApiResponse<Void> delete(@PathVariable long id) {
        dataSourceService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('datasource:write')")
    public ApiResponse<TestConnectionResult> test(@PathVariable long id) {
        return ApiResponse.ok(dataSourceService.test(id));
    }

    @GetMapping("/{id}/schema")
    @PreAuthorize("hasAuthority('datasource:read')")
    public ApiResponse<SchemaResponse> schema(@PathVariable long id) {
        return ApiResponse.ok(dataSourceService.schema(id));
    }

    @PostMapping("/{id}/query")
    @PreAuthorize("hasAuthority('datasource:query')")
    public ApiResponse<SqlQueryResult> query(@PathVariable long id,
                                             @Valid @RequestBody SqlQueryRequest request) {
        int maxRows = request.maxRows() == null ? 100 : request.maxRows();
        return ApiResponse.ok(sqlQueryService.query(id, request.sql(), maxRows));
    }

    @GetMapping("/{id}/query-logs")
    @PreAuthorize("hasAuthority('datasource:read')")
    public ApiResponse<PageResult<QueryLogResponse>> queryLogs(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(dataSourceService.queryLogs(id, page, size));
    }
}
