package com.agentforge.audit.usage;

import com.agentforge.audit.model.ModelPriceEntity;
import com.agentforge.audit.model.TokenUsageEntity;
import com.agentforge.audit.repository.ModelPriceRepository;
import com.agentforge.audit.repository.TokenUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Token 用量上报实现：异步落库（{@code tokenUsageExecutor}），任何异常只记日志，不影响模型调用。
 *
 * <p>成本按 {@code ai_model_price} 的每 1K token 单价估算，无价格记录时按 0 处理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageServiceImpl implements TokenUsageService {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final int COST_SCALE = 6;

    private final TokenUsageRepository tokenUsageRepository;
    private final ModelPriceRepository modelPriceRepository;

    @Override
    @Async("tokenUsageExecutor")
    public void record(TokenUsageEvent event) {
        if (event == null) {
            return;
        }
        try {
            TokenUsageEntity entity = toEntity(event);
            entity.setCost(calculateCost(event));
            tokenUsageRepository.save(entity);
        } catch (Exception ex) {
            log.warn("Token 用量异步落库失败，已忽略: traceId={}, model={}, reason={}",
                    event.traceId(), event.modelName(), ex.getMessage());
        }
    }

    private TokenUsageEntity toEntity(TokenUsageEvent event) {
        TokenUsageEntity entity = new TokenUsageEntity();
        entity.setTenantId(event.tenantId() == null ? 1L : event.tenantId());
        entity.setUserId(event.userId());
        entity.setAgentId(event.agentId());
        entity.setRunId(event.runId());
        entity.setSessionId(event.sessionId());
        entity.setModelId(event.modelId());
        entity.setModelName(event.modelName());
        entity.setProvider(event.provider());
        entity.setPromptTokens(event.promptTokens());
        entity.setCompletionTokens(event.completionTokens());
        entity.setTotalTokens(event.totalTokens());
        entity.setStream(event.stream());
        entity.setSuccess(event.success());
        entity.setLatencyMs(event.latencyMs());
        entity.setTraceId(event.traceId());
        return entity;
    }

    private BigDecimal calculateCost(TokenUsageEvent event) {
        if (event.totalTokens() <= 0 || event.provider() == null || event.modelName() == null) {
            return BigDecimal.ZERO;
        }
        try {
            Optional<ModelPriceEntity> price = modelPriceRepository
                    .findFirstByProviderIgnoreCaseAndModelNameIgnoreCase(event.provider(), event.modelName());
            if (price.isEmpty()) {
                return BigDecimal.ZERO;
            }
            BigDecimal inputPrice = price.get().getInputPricePer1k() == null
                    ? BigDecimal.ZERO : price.get().getInputPricePer1k();
            BigDecimal outputPrice = price.get().getOutputPricePer1k() == null
                    ? BigDecimal.ZERO : price.get().getOutputPricePer1k();
            BigDecimal inputCost = inputPrice.multiply(BigDecimal.valueOf(event.promptTokens()))
                    .divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP);
            BigDecimal outputCost = outputPrice.multiply(BigDecimal.valueOf(event.completionTokens()))
                    .divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP);
            return inputCost.add(outputCost).setScale(COST_SCALE, RoundingMode.HALF_UP);
        } catch (Exception ex) {
            log.warn("Token 成本计算失败，按 0 处理: {}", ex.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
