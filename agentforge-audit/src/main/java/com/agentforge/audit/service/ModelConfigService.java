package com.agentforge.audit.service;

import com.agentforge.audit.api.ModelConfigRequest;
import com.agentforge.audit.api.ModelConfigResponse;
import com.agentforge.audit.api.ModelTestResponse;
import com.agentforge.audit.gateway.ModelProviderFactoryImpl;
import com.agentforge.audit.model.ModelConfigEntity;
import com.agentforge.audit.repository.ModelConfigRepository;
import com.agentforge.common.crypto.CryptoUtils;
import com.agentforge.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 模型配置管理：CRUD + 连通性测试。API Key 只以 AES-GCM 密文入库，任何响应都不回显明文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("MOCK", "DASHSCOPE", "OPENAI_COMPATIBLE");
    private static final String API_KEY_MASK = "******";

    private final ModelConfigRepository modelConfigRepository;
    private final ModelProviderFactoryImpl modelProviderFactory;

    public List<ModelConfigResponse> list() {
        return modelConfigRepository.findAllByOrderByDefaultModelDescIdAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    public ModelConfigResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional
    public ModelConfigResponse create(ModelConfigRequest request) {
        if (modelConfigRepository.existsByName(request.name().trim())) {
            throw BizException.conflict("模型名称已存在: " + request.name());
        }
        ModelConfigEntity entity = new ModelConfigEntity();
        apply(entity, request, true);
        if (Boolean.TRUE.equals(entity.getDefaultModel())) {
            clearOtherDefaults(null);
        }
        modelConfigRepository.save(entity);
        modelProviderFactory.evictAll();
        log.info("创建模型配置: id={}, provider={}, modelName={}",
                entity.getId(), entity.getProvider(), entity.getModelName());
        return toResponse(entity);
    }

    @Transactional
    public ModelConfigResponse update(Long id, ModelConfigRequest request) {
        ModelConfigEntity entity = find(id);
        String name = request.name().trim();
        if (modelConfigRepository.existsByNameAndIdNot(name, id)) {
            throw BizException.conflict("模型名称已存在: " + request.name());
        }
        apply(entity, request, false);
        if (Boolean.TRUE.equals(entity.getDefaultModel())) {
            clearOtherDefaults(id);
        }
        modelConfigRepository.save(entity);
        modelProviderFactory.evictAll();
        log.info("更新模型配置: id={}, provider={}, modelName={}", id, entity.getProvider(), entity.getModelName());
        return toResponse(entity);
    }

    @Transactional
    public void delete(Long id) {
        ModelConfigEntity entity = find(id);
        modelConfigRepository.delete(entity);
        modelProviderFactory.evictAll();
        log.info("删除模型配置: id={}, name={}", id, entity.getName());
    }

    /**
     * 连通性测试：用工厂构建的模型发送 ping，返回延迟与回显；失败不抛异常，返回 success=false。
     */
    public ModelTestResponse test(Long id) {
        ModelConfigEntity entity = find(id);
        ChatModel model = modelProviderFactory.getChatModel(id);
        long start = System.nanoTime();
        try {
            ChatResponse response = model.call(new Prompt("ping"));
            long latencyMs = elapsedMs(start);
            String reply = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
            log.info("模型测试成功: id={}, latencyMs={}", id, latencyMs);
            return new ModelTestResponse(true, latencyMs, entity.getModelName(), reply, "模型连通正常");
        } catch (Exception ex) {
            long latencyMs = elapsedMs(start);
            log.warn("模型测试失败: id={}, latencyMs={}, reason={}", id, latencyMs, ex.getMessage());
            return new ModelTestResponse(false, latencyMs, entity.getModelName(), null,
                    "模型调用失败: " + ex.getMessage());
        }
    }

    private ModelConfigEntity find(Long id) {
        if (id == null) {
            throw BizException.badRequest("模型 ID 不能为空");
        }
        return modelConfigRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("模型配置不存在: " + id));
    }

    private void apply(ModelConfigEntity entity, ModelConfigRequest request, boolean creating) {
        String provider = normalizeProvider(request.provider());
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw BizException.badRequest("provider 仅支持 MOCK / DASHSCOPE / OPENAI_COMPATIBLE");
        }
        entity.setName(request.name().trim());
        entity.setProvider(provider);
        entity.setModelName(request.modelName().trim());
        entity.setBaseUrl(StringUtils.hasText(request.baseUrl()) ? request.baseUrl().trim() : null);
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setDefaultModel(request.defaultModel() != null && request.defaultModel());
        entity.setTemperature(request.temperature());
        entity.setMaxTokens(request.maxTokens());
        if (StringUtils.hasText(request.apiKey())) {
            entity.setApiKeyEncrypted(CryptoUtils.encrypt(request.apiKey().trim()));
        } else if (creating) {
            entity.setApiKeyEncrypted(null);
        }
    }

    private void clearOtherDefaults(Long keepId) {
        modelConfigRepository.findAll().stream()
                .filter(other -> Boolean.TRUE.equals(other.getDefaultModel()))
                .filter(other -> keepId == null || !keepId.equals(other.getId()))
                .forEach(other -> {
                    other.setDefaultModel(false);
                    modelConfigRepository.save(other);
                });
    }

    private ModelConfigResponse toResponse(ModelConfigEntity entity) {
        boolean hasApiKey = StringUtils.hasText(entity.getApiKeyEncrypted());
        return new ModelConfigResponse(
                entity.getId(),
                entity.getName(),
                entity.getProvider(),
                entity.getModelName(),
                entity.getBaseUrl(),
                entity.getEnabled(),
                entity.getDefaultModel(),
                entity.getTemperature(),
                entity.getMaxTokens(),
                hasApiKey,
                hasApiKey ? API_KEY_MASK : null,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
