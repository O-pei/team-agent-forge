package com.agentforge.audit.gateway;

import com.agentforge.audit.gateway.mock.MockChatModel;
import com.agentforge.audit.model.ModelConfigEntity;
import com.agentforge.audit.repository.ModelConfigRepository;
import com.agentforge.audit.usage.TokenUsageService;
import com.agentforge.audit.usage.UsageRecordingChatModel;
import com.agentforge.common.crypto.CryptoUtils;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型工厂实现：按数据库配置构建 Spring AI {@link ChatModel}。
 *
 * <ul>
 *   <li>MOCK / 无可用配置 / provider 未接入 / DASHSCOPE 无 API Key → {@link MockChatModel}（零配置可跑）；</li>
 *   <li>DASHSCOPE → {@code DashScopeChatModel}（API Key 从 AES-GCM 密文解密，仅在内存使用）；</li>
 *   <li>所有模型返回前统一用 {@link UsageRecordingChatModel} 包装，保证审计链路不漏；</li>
 *   <li>构建结果按 modelId 缓存，配置变更后由 {@link #evict(Long)} / {@link #evictAll()} 清理。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelProviderFactoryImpl implements ModelProviderFactory {

    public static final String PROVIDER_MOCK = "MOCK";
    public static final String PROVIDER_DASHSCOPE = "DASHSCOPE";
    public static final String PROVIDER_OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";

    private static final String DEFAULT_MOCK_MODEL_NAME = "mock-chat";

    private final ModelConfigRepository modelConfigRepository;
    private final TokenUsageService tokenUsageService;

    private final Map<Long, ChatModel> cache = new ConcurrentHashMap<>();

    @Override
    public ChatModel getChatModel(Long modelId) {
        ModelConfigEntity config = resolveConfig(modelId);
        if (config == null) {
            log.debug("未找到可用模型配置（modelId={}），使用内置 MockChatModel", modelId);
            return wrap(new MockChatModel(DEFAULT_MOCK_MODEL_NAME), null, DEFAULT_MOCK_MODEL_NAME, PROVIDER_MOCK);
        }
        return cache.computeIfAbsent(config.getId(), id -> buildModel(config));
    }

    @Override
    public ChatModel getDefaultChatModel() {
        return getChatModel(getDefaultModelId());
    }

    @Override
    public Long getDefaultModelId() {
        ModelConfigEntity config = resolveConfig(null);
        return config == null ? null : config.getId();
    }

    @Override
    public ChatModelInfo getModelInfo(Long modelId) {
        ModelConfigEntity config = modelId == null
                ? resolveConfig(null)
                : modelConfigRepository.findById(modelId).orElse(null);
        if (config == null) {
            return new ChatModelInfo(null, "Mock 默认模型", PROVIDER_MOCK, DEFAULT_MOCK_MODEL_NAME, true, true);
        }
        return new ChatModelInfo(config.getId(), config.getName(), normalizeProvider(config.getProvider()),
                config.getModelName(),
                Boolean.TRUE.equals(config.getDefaultModel()),
                Boolean.TRUE.equals(config.getEnabled()));
    }

    /** 配置变更后清理单个模型缓存。 */
    public void evict(Long modelId) {
        if (modelId != null) {
            cache.remove(modelId);
        }
    }

    /** 批量变更（默认模型切换等）后清理全部缓存。 */
    public void evictAll() {
        cache.clear();
    }

    private ModelConfigEntity resolveConfig(Long modelId) {
        if (modelId != null) {
            return modelConfigRepository.findById(modelId)
                    .filter(config -> Boolean.TRUE.equals(config.getEnabled()))
                    .orElse(null);
        }
        Optional<ModelConfigEntity> defaultModel = modelConfigRepository
                .findFirstByDefaultModelTrueAndEnabledTrueOrderByIdAsc();
        if (defaultModel.isPresent()) {
            return defaultModel.get();
        }
        return modelConfigRepository.findFirstByEnabledTrueOrderByIdAsc().orElse(null);
    }

    private ChatModel buildModel(ModelConfigEntity config) {
        String provider = normalizeProvider(config.getProvider());
        ChatModel delegate = switch (provider) {
            case PROVIDER_DASHSCOPE -> buildDashScope(config);
            case PROVIDER_OPENAI_COMPATIBLE -> {
                log.warn("模型 {} provider=OPENAI_COMPATIBLE 暂未接入（未引入 spring-ai-openai），自动回退 MockChatModel",
                        config.getId());
                yield new MockChatModel(modelName(config));
            }
            case PROVIDER_MOCK -> new MockChatModel(modelName(config));
            default -> {
                log.warn("模型 {} provider={} 不支持，自动回退 MockChatModel", config.getId(), provider);
                yield new MockChatModel(modelName(config));
            }
        };
        return wrap(delegate, config.getId(), modelName(config), provider);
    }

    private ChatModel buildDashScope(ModelConfigEntity config) {
        String apiKey;
        try {
            apiKey = CryptoUtils.decrypt(config.getApiKeyEncrypted());
        } catch (Exception ex) {
            log.warn("模型 {} API Key 解密失败，自动回退 MockChatModel: {}", config.getId(), ex.getMessage());
            return new MockChatModel(modelName(config));
        }
        if (!StringUtils.hasText(apiKey)) {
            log.warn("模型 {} provider=DASHSCOPE 未配置 API Key，自动回退 MockChatModel", config.getId());
            return new MockChatModel(modelName(config));
        }
        try {
            DashScopeApi.Builder apiBuilder = DashScopeApi.builder().apiKey(apiKey);
            if (StringUtils.hasText(config.getBaseUrl())) {
                apiBuilder.baseUrl(config.getBaseUrl());
            }
            DashScopeChatOptions options = new DashScopeChatOptions();
            options.setModel(modelName(config));
            if (config.getTemperature() != null) {
                options.setTemperature(config.getTemperature());
            }
            if (config.getMaxTokens() != null) {
                options.setMaxTokens(config.getMaxTokens());
            }
            return DashScopeChatModel.builder()
                    .dashScopeApi(apiBuilder.build())
                    .defaultOptions(options)
                    .build();
        } catch (Exception ex) {
            log.warn("模型 {} DashScope 客户端构建失败，自动回退 MockChatModel: {}", config.getId(), ex.getMessage());
            return new MockChatModel(modelName(config));
        }
    }

    private ChatModel wrap(ChatModel delegate, Long modelId, String modelName, String provider) {
        return new UsageRecordingChatModel(delegate, modelId, modelName, provider, tokenUsageService);
    }

    private static String modelName(ModelConfigEntity config) {
        return StringUtils.hasText(config.getModelName()) ? config.getModelName() : DEFAULT_MOCK_MODEL_NAME;
    }

    private static String normalizeProvider(String provider) {
        return provider == null ? PROVIDER_MOCK : provider.trim().toUpperCase(Locale.ROOT);
    }
}
