package com.agentforge.audit.service;

import com.agentforge.audit.api.AuditOverview;
import com.agentforge.audit.api.BreakdownItem;
import com.agentforge.audit.api.TrendPoint;
import com.agentforge.audit.api.UsageRecordResponse;
import com.agentforge.audit.api.UsageSummary;
import com.agentforge.audit.model.TokenUsageEntity;
import com.agentforge.audit.repository.ModelConfigRepository;
import com.agentforge.audit.repository.TokenUsageRepository;
import com.agentforge.common.api.PageResult;
import com.agentforge.common.exception.BizException;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Token 用量查询：汇总、趋势、分解、明细分页与 CSV 导出。所有查询只读。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UsageQueryService {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");

    private final TokenUsageRepository tokenUsageRepository;
    private final ModelConfigRepository modelConfigRepository;

    public UsageSummary summary(LocalDateTime from, LocalDateTime to) {
        DateRange range = range(from, to);
        List<Object[]> rows = tokenUsageRepository.summarize(range.from(), range.to());
        if (rows == null || rows.isEmpty()) {
            return new UsageSummary(0, 0, 0, BigDecimal.ZERO, 0);
        }
        Object[] row = rows.get(0);
        if (row == null || row.length < 5) {
            return new UsageSummary(0, 0, 0, BigDecimal.ZERO, 0);
        }
        return new UsageSummary(number(row[0]), number(row[1]), number(row[2]), decimal(row[3]), number(row[4]));
    }

    public List<TrendPoint> trend(LocalDateTime from, LocalDateTime to, String granularity) {
        String normalized = granularity == null ? "day" : granularity.trim().toLowerCase(Locale.ROOT);
        if (!"day".equals(normalized) && !"hour".equals(normalized)) {
            throw BizException.badRequest("granularity 仅支持 day / hour");
        }
        DateRange range = range(from, to);
        Map<String, Aggregate> buckets = new LinkedHashMap<>();
        for (Object[] row : tokenUsageRepository.findRows(range.from(), range.to())) {
            LocalDateTime createdAt = (LocalDateTime) row[0];
            String key = "hour".equals(normalized)
                    ? createdAt.format(HOUR_FORMAT)
                    : createdAt.toLocalDate().format(DAY_FORMAT);
            buckets.computeIfAbsent(key, ignored -> new Aggregate())
                    .add(number(row[1]), number(row[2]), number(row[3]), decimal(row[4]));
        }
        return buckets.entrySet().stream()
                .map(entry -> new TrendPoint(entry.getKey(), entry.getValue().promptTokens,
                        entry.getValue().completionTokens, entry.getValue().totalTokens,
                        entry.getValue().requests, entry.getValue().cost))
                .toList();
    }

    public List<BreakdownItem> breakdown(LocalDateTime from, LocalDateTime to, String groupBy) {
        String normalized = groupBy == null ? "model" : groupBy.trim().toLowerCase(Locale.ROOT);
        if (!"model".equals(normalized) && !"agent".equals(normalized) && !"user".equals(normalized)) {
            throw BizException.badRequest("groupBy 仅支持 model / agent / user");
        }
        DateRange range = range(from, to);
        Map<String, Aggregate> groups = new LinkedHashMap<>();
        for (Object[] row : tokenUsageRepository.findRows(range.from(), range.to())) {
            long promptTokens = number(row[1]);
            long completionTokens = number(row[2]);
            long totalTokens = number(row[3]);
            BigDecimal cost = decimal(row[4]);
            String key = switch (normalized) {
                case "agent" -> row[6] == null ? "unknown" : String.valueOf(row[6]);
                case "user" -> row[7] == null ? "unknown" : String.valueOf(row[7]);
                default -> row[5] == null ? "unknown" : String.valueOf(row[5]);
            };
            groups.computeIfAbsent(key, ignored -> new Aggregate())
                    .add(promptTokens, completionTokens, totalTokens, cost);
        }
        return groups.entrySet().stream()
                .map(entry -> new BreakdownItem(entry.getKey(), entry.getValue().promptTokens,
                        entry.getValue().completionTokens, entry.getValue().totalTokens,
                        entry.getValue().requests, entry.getValue().cost))
                .sorted(Comparator.comparingLong(BreakdownItem::totalTokens).reversed())
                .toList();
    }

    public PageResult<UsageRecordResponse> records(int page, int size, LocalDateTime from, LocalDateTime to,
                                                   Long modelId, Long agentId, Long userId) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        DateRange range = range(from, to);
        Specification<TokenUsageEntity> specification = specification(range, modelId, agentId, userId);
        Page<TokenUsageEntity> result = tokenUsageRepository.findAll(specification,
                PageRequest.of(safePage - 1, safeSize,
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        List<UsageRecordResponse> records = result.getContent().stream().map(this::toResponse).toList();
        return PageResult.of(records, result.getTotalElements(), safePage, safeSize);
    }

    /** 导出 CSV（UTF-8 BOM，Excel 可直接打开）。 */
    public byte[] exportCsv(LocalDateTime from, LocalDateTime to) {
        DateRange range = range(from, to);
        List<TokenUsageEntity> rows = tokenUsageRepository.findAll(specification(range, null, null, null),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("id,traceId,tenantId,userId,agentId,runId,sessionId,modelId,modelName,provider,")
                .append("promptTokens,completionTokens,totalTokens,cost,stream,success,latencyMs,createdAt\n");
        for (TokenUsageEntity row : rows) {
            csv.append(csvValue(row.getId()))
                    .append(',').append(csvValue(row.getTraceId()))
                    .append(',').append(csvValue(row.getTenantId()))
                    .append(',').append(csvValue(row.getUserId()))
                    .append(',').append(csvValue(row.getAgentId()))
                    .append(',').append(csvValue(row.getRunId()))
                    .append(',').append(csvValue(row.getSessionId()))
                    .append(',').append(csvValue(row.getModelId()))
                    .append(',').append(csvValue(row.getModelName()))
                    .append(',').append(csvValue(row.getProvider()))
                    .append(',').append(row.getPromptTokens())
                    .append(',').append(row.getCompletionTokens())
                    .append(',').append(row.getTotalTokens())
                    .append(',').append(row.getCost() == null ? "0" : row.getCost().toPlainString())
                    .append(',').append(row.isStream())
                    .append(',').append(row.isSuccess())
                    .append(',').append(row.getLatencyMs())
                    .append(',').append(row.getCreatedAt() == null ? "" : row.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .append('\n');
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public AuditOverview overview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        UsageSummary today = summary(todayStart, todayStart.plusDays(1));
        UsageSummary total = summary(null, null);
        long totalModels = modelConfigRepository.count();
        long enabledModels = modelConfigRepository.countByEnabledTrue();
        return new AuditOverview(totalModels, enabledModels,
                today.requests(), today.totalTokens(), today.totalCost(),
                total.requests(), total.totalTokens(), total.totalCost());
    }

    private Specification<TokenUsageEntity> specification(DateRange range, Long modelId, Long agentId, Long userId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), range.from()));
            predicates.add(builder.lessThan(root.get("createdAt"), range.to()));
            if (modelId != null) {
                predicates.add(builder.equal(root.get("modelId"), modelId));
            }
            if (agentId != null) {
                predicates.add(builder.equal(root.get("agentId"), agentId));
            }
            if (userId != null) {
                predicates.add(builder.equal(root.get("userId"), userId));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private UsageRecordResponse toResponse(TokenUsageEntity entity) {
        return new UsageRecordResponse(
                entity.getId(),
                entity.getTraceId(),
                entity.getTenantId(),
                entity.getUserId(),
                entity.getAgentId(),
                entity.getRunId(),
                entity.getSessionId(),
                entity.getModelId(),
                entity.getModelName(),
                entity.getProvider(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(),
                entity.getTotalTokens(),
                entity.getCost(),
                entity.isStream(),
                entity.isSuccess(),
                entity.getLatencyMs(),
                entity.getCreatedAt());
    }

    private DateRange range(LocalDateTime from, LocalDateTime to) {
        LocalDateTime safeTo = to == null ? LocalDateTime.now().plusSeconds(1) : to;
        LocalDateTime safeFrom = from == null ? safeTo.minusDays(DEFAULT_DAYS) : from;
        if (!safeFrom.isBefore(safeTo)) {
            throw BizException.badRequest("时间范围不合法：from 必须早于 to");
        }
        return new DateRange(safeFrom, safeTo);
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal result = value instanceof BigDecimal bigDecimal
                ? bigDecimal : new BigDecimal(value.toString());
        return result.setScale(6, RoundingMode.HALF_UP);
    }

    private static String csvValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private record DateRange(LocalDateTime from, LocalDateTime to) {
    }

    private static final class Aggregate {
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long requests;
        private BigDecimal cost = BigDecimal.ZERO;

        void add(long prompt, long completion, long total, BigDecimal amount) {
            this.promptTokens += prompt;
            this.completionTokens += completion;
            this.totalTokens += total;
            this.requests++;
            this.cost = this.cost.add(amount == null ? BigDecimal.ZERO : amount);
        }
    }
}
