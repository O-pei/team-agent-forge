package com.agentforge.audit.usage;

import com.agentforge.audit.model.ModelPriceEntity;
import com.agentforge.audit.model.TokenUsageEntity;
import com.agentforge.audit.repository.ModelPriceRepository;
import com.agentforge.audit.repository.TokenUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TokenUsageServiceImpl 事件组装 + 成本估算 + 失败降级测试。
 */
@ExtendWith(MockitoExtension.class)
class TokenUsageServiceImplTest {

    @Mock
    private TokenUsageRepository tokenUsageRepository;

    @Mock
    private ModelPriceRepository modelPriceRepository;

    @Test
    void assemblesEntityAndCalculatesCostFromPriceTable() {
        ModelPriceEntity price = new ModelPriceEntity();
        price.setProvider("DASHSCOPE");
        price.setModelName("qwen-plus");
        price.setInputPricePer1k(new BigDecimal("0.004"));
        price.setOutputPricePer1k(new BigDecimal("0.012"));
        when(modelPriceRepository.findFirstByProviderIgnoreCaseAndModelNameIgnoreCase("DASHSCOPE", "qwen-plus"))
                .thenReturn(Optional.of(price));

        TokenUsageServiceImpl service = new TokenUsageServiceImpl(tokenUsageRepository, modelPriceRepository);
        service.record(new TokenUsageEvent(
                1L, 2L, 3L, 4L, "session-1", 5L, "qwen-plus", "DASHSCOPE",
                1000L, 2000L, 3000L, true, true, 123L, "trace-1"));

        ArgumentCaptor<TokenUsageEntity> captor = ArgumentCaptor.forClass(TokenUsageEntity.class);
        verify(tokenUsageRepository).save(captor.capture());
        TokenUsageEntity entity = captor.getValue();
        assertEquals(1L, entity.getTenantId());
        assertEquals(2L, entity.getUserId());
        assertEquals(3L, entity.getAgentId());
        assertEquals(4L, entity.getRunId());
        assertEquals("session-1", entity.getSessionId());
        assertEquals(5L, entity.getModelId());
        assertEquals("qwen-plus", entity.getModelName());
        assertEquals("DASHSCOPE", entity.getProvider());
        assertEquals(1000L, entity.getPromptTokens());
        assertEquals(2000L, entity.getCompletionTokens());
        assertEquals(3000L, entity.getTotalTokens());
        assertTrue(entity.isStream());
        assertTrue(entity.isSuccess());
        assertEquals(123L, entity.getLatencyMs());
        assertEquals("trace-1", entity.getTraceId());
        // 0.004 * 1000/1000 + 0.012 * 2000/1000 = 0.028
        assertEquals(new BigDecimal("0.028000"), entity.getCost());
    }

    @Test
    void repositoryFailureIsSwallowed() {
        when(modelPriceRepository.findFirstByProviderIgnoreCaseAndModelNameIgnoreCase(any(), any()))
                .thenReturn(Optional.empty());
        when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                .thenThrow(new IllegalStateException("db down"));

        TokenUsageServiceImpl service = new TokenUsageServiceImpl(tokenUsageRepository, modelPriceRepository);
        assertDoesNotThrow(() -> service.record(new TokenUsageEvent(
                1L, null, null, null, "s", 1L, "mock-chat", "MOCK",
                1L, 1L, 2L, false, true, 1L, null)));
    }

    @Test
    void missingPriceTableFallsBackToZeroCost() {
        when(modelPriceRepository.findFirstByProviderIgnoreCaseAndModelNameIgnoreCase("MOCK", "mock-chat"))
                .thenReturn(Optional.empty());

        TokenUsageServiceImpl service = new TokenUsageServiceImpl(tokenUsageRepository, modelPriceRepository);
        service.record(new TokenUsageEvent(
                1L, null, null, null, "s", 1L, "mock-chat", "MOCK",
                10L, 20L, 30L, false, true, 5L, null));

        ArgumentCaptor<TokenUsageEntity> captor = ArgumentCaptor.forClass(TokenUsageEntity.class);
        verify(tokenUsageRepository).save(captor.capture());
        assertEquals(BigDecimal.ZERO, captor.getValue().getCost());
    }
}
