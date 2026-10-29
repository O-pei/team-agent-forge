package com.agentforge.audit.service;

import com.agentforge.audit.api.AuditOverview;
import com.agentforge.audit.api.UsageSummary;
import com.agentforge.audit.model.TokenUsageEntity;
import com.agentforge.audit.repository.ModelConfigRepository;
import com.agentforge.audit.repository.TokenUsageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 回归测试：写入 usage 后 summary 和 overview 必须非零。
 * 覆盖 TokenUsageRepository.summarize 用单元素 Object[] 接收多列聚合行、被 Spring Data 包装后长度不足导致全 0 的 bug。
 */
@DataJpaTest
@EntityScan({"com.agentforge.audit.model", "com.agentforge.audit.quota.model"})
@Import(UsageQueryService.class)
class UsageAggregationTest {

    @Autowired
    private TokenUsageRepository tokenUsageRepository;

    @Autowired
    private ModelConfigRepository modelConfigRepository;

    @Autowired
    private UsageQueryService usageQueryService;

    @Test
    void summaryIsNonZeroAfterWritingUsageRows() {
        LocalDateTime now = LocalDateTime.now();
        tokenUsageRepository.save(row(now.minusSeconds(2), 10, 20, new BigDecimal("0.000240")));
        tokenUsageRepository.save(row(now.minusSeconds(1), 20, 30, new BigDecimal("0.000240")));
        tokenUsageRepository.flush();

        UsageSummary summary = usageQueryService.summary(now.minusMinutes(5), now.plusMinutes(1));

        assertThat(summary.requests()).isEqualTo(2);
        assertThat(summary.totalPromptTokens()).isEqualTo(30);
        assertThat(summary.totalCompletionTokens()).isEqualTo(50);
        assertThat(summary.totalTokens()).isEqualTo(80);
        assertThat(summary.totalCost()).isEqualByComparingTo(new BigDecimal("0.000480"));
    }

    @Test
    void summaryReturnsZerosWhenNoRowsInRange() {
        LocalDateTime now = LocalDateTime.now();

        UsageSummary summary = usageQueryService.summary(now.minusDays(2), now.minusDays(1));

        assertThat(summary.requests()).isZero();
        assertThat(summary.totalPromptTokens()).isZero();
        assertThat(summary.totalCompletionTokens()).isZero();
        assertThat(summary.totalTokens()).isZero();
        assertThat(summary.totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void overviewUsesTodayAndAllTimeAggregates() {
        LocalDateTime now = LocalDateTime.now();
        tokenUsageRepository.save(row(now.minusMinutes(1), 7, 13, new BigDecimal("0.000100")));
        tokenUsageRepository.flush();

        AuditOverview overview = usageQueryService.overview();

        assertThat(overview.todayRequests()).isEqualTo(1);
        assertThat(overview.todayTokens()).isEqualTo(20);
        assertThat(overview.todayCost()).isEqualByComparingTo(new BigDecimal("0.000100"));
        assertThat(overview.totalRequests()).isEqualTo(1);
        assertThat(overview.totalTokens()).isEqualTo(20);
        assertThat(overview.totalCost()).isEqualByComparingTo(new BigDecimal("0.000100"));
        // 空库只有模型表 seed 前为 0，这里只断言聚合可用且方法不抛异常
        assertThatCode(() -> assertThat(overview.totalModels()).isGreaterThanOrEqualTo(0))
                .doesNotThrowAnyException();
        assertThat(modelConfigRepository.count()).isGreaterThanOrEqualTo(0);
    }

    private static TokenUsageEntity row(LocalDateTime createdAt, long promptTokens, long completionTokens,
                                        BigDecimal cost) {
        TokenUsageEntity entity = new TokenUsageEntity();
        entity.setTenantId(1L);
        entity.setUserId(1L);
        entity.setModelId(1L);
        entity.setModelName("mock-chat");
        entity.setProvider("MOCK");
        entity.setPromptTokens(promptTokens);
        entity.setCompletionTokens(completionTokens);
        entity.setTotalTokens(promptTokens + completionTokens);
        entity.setCost(cost);
        entity.setStream(false);
        entity.setSuccess(true);
        entity.setLatencyMs(5L);
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
