package com.agentforge.audit.controller;

import com.agentforge.audit.api.AuditOverview;
import com.agentforge.audit.api.BreakdownItem;
import com.agentforge.audit.api.TrendPoint;
import com.agentforge.audit.api.UsageRecordResponse;
import com.agentforge.audit.api.UsageSummary;
import com.agentforge.audit.service.UsageQueryService;
import com.agentforge.common.api.ApiResponse;
import com.agentforge.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token usage audit endpoints (contract 2.2). All require audit:read.
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('audit:read')")
public class AuditController {

    private final UsageQueryService usageQueryService;

    @GetMapping("/usage/summary")
    public ApiResponse<UsageSummary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(usageQueryService.summary(from, to));
    }

    @GetMapping("/usage/trend")
    public ApiResponse<List<TrendPoint>> trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "day") String granularity) {
        return ApiResponse.ok(usageQueryService.trend(from, to, granularity));
    }

    @GetMapping("/usage/breakdown")
    public ApiResponse<List<BreakdownItem>> breakdown(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "model") String groupBy) {
        return ApiResponse.ok(usageQueryService.breakdown(from, to, groupBy));
    }

    @GetMapping("/usage/records")
    public ApiResponse<PageResult<UsageRecordResponse>> records(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Long modelId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) Long userId) {
        return ApiResponse.ok(usageQueryService.records(page, size, from, to, modelId, agentId, userId));
    }

    @GetMapping("/usage/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        byte[] content = usageQueryService.exportCsv(from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"token-usage.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(content);
    }

    @GetMapping("/overview")
    public ApiResponse<AuditOverview> overview() {
        return ApiResponse.ok(usageQueryService.overview());
    }
}
