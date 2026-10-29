package com.agentforge.agent.service;

import com.agentforge.agent.domain.AgentType;
import com.agentforge.agent.dto.AgentCreateRequest;
import com.agentforge.agent.dto.AgentResponse;
import com.agentforge.agent.dto.AgentSummary;
import com.agentforge.agent.dto.AgentUpdateRequest;
import com.agentforge.agent.dto.AgentVersionResponse;
import com.agentforge.agent.entity.AgentDefinitionEntity;
import com.agentforge.agent.entity.AgentVersionEntity;
import com.agentforge.agent.repository.AgentDefinitionRepository;
import com.agentforge.agent.repository.AgentVersionRepository;
import com.agentforge.agent.tool.AgentToolRegistry;
import com.agentforge.common.api.PageResult;
import com.agentforge.common.context.TenantContext;
import com.agentforge.common.exception.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agent 定义 CRUD 与版本管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AgentDefinitionRepository definitionRepository;
    private final AgentVersionRepository versionRepository;
    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PageResult<AgentSummary> page(int page, int size, String keyword) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Long tenantId = TenantContext.getCurrentTenantId();
        PageRequest pageRequest = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "id"));
        Page<AgentDefinitionEntity> result = keyword == null || keyword.isBlank()
                ? definitionRepository.findByTenantIdAndDeletedFalse(tenantId, pageRequest)
                : definitionRepository.findByTenantIdAndDeletedFalseAndNameContainingIgnoreCase(
                tenantId, keyword.trim(), pageRequest);
        List<AgentSummary> records = result.getContent().stream().map(this::toSummary).toList();
        return PageResult.of(records, result.getTotalElements(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public AgentResponse get(Long id) {
        return toResponse(load(id));
    }

    @Transactional
    public AgentResponse create(AgentCreateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw BizException.badRequest("name 不能为空");
        }
        Long tenantId = TenantContext.getCurrentTenantId();
        if (definitionRepository.existsByTenantIdAndNameAndDeletedFalse(tenantId, request.name().trim())) {
            throw BizException.conflict("Agent 名称已存在: " + request.name());
        }
        validateRequest(request.type(), request.tools(), request.subAgentIds());

        AgentDefinitionEntity entity = new AgentDefinitionEntity();
        entity.setTenantId(tenantId);
        entity.setName(request.name().trim());
        entity.setDescription(request.description());
        entity.setType(AgentType.from(request.type()).name());
        entity.setSystemPrompt(request.systemPrompt());
        entity.setModelId(request.modelId());
        entity.setTemperature(request.temperature());
        entity.setMaxIterations(request.maxIterations());
        entity.setToolsJson(writeJson(request.tools()));
        entity.setSubAgentIdsJson(writeJson(request.subAgentIds()));
        entity.setOutputType(request.outputType());
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setDeleted(false);
        entity.setCurrentVersion(1);
        AgentDefinitionEntity saved = definitionRepository.save(entity);
        saveVersion(saved, 1);
        log.info("创建 Agent id={} name={} tenantId={}", saved.getId(), saved.getName(), tenantId);
        return toResponse(saved);
    }

    @Transactional
    public AgentResponse update(Long id, AgentUpdateRequest request) {
        AgentDefinitionEntity entity = load(id);
        if (request == null) {
            throw BizException.badRequest("请求体不能为空");
        }
        if (request.name() != null && !request.name().isBlank()
                && !request.name().trim().equals(entity.getName())) {
            Long tenantId = TenantContext.getCurrentTenantId();
            if (definitionRepository.existsByTenantIdAndNameAndDeletedFalse(tenantId, request.name().trim())) {
                throw BizException.conflict("Agent 名称已存在: " + request.name());
            }
            entity.setName(request.name().trim());
        }
        if (request.type() != null) {
            validateRequest(request.type(), request.tools(), request.subAgentIds());
            entity.setType(AgentType.from(request.type()).name());
        }
        if (request.tools() != null) {
            validateTools(request.tools());
            entity.setToolsJson(writeJson(request.tools()));
        }
        if (request.subAgentIds() != null) {
            entity.setSubAgentIdsJson(writeJson(request.subAgentIds()));
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        if (request.systemPrompt() != null) {
            entity.setSystemPrompt(request.systemPrompt());
        }
        if (request.modelId() != null) {
            entity.setModelId(request.modelId());
        }
        if (request.temperature() != null) {
            entity.setTemperature(request.temperature());
        }
        if (request.maxIterations() != null) {
            entity.setMaxIterations(request.maxIterations());
        }
        if (request.outputType() != null) {
            entity.setOutputType(request.outputType());
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        int nextVersion = (entity.getCurrentVersion() == null ? 0 : entity.getCurrentVersion()) + 1;
        entity.setCurrentVersion(nextVersion);
        AgentDefinitionEntity saved = definitionRepository.save(entity);
        saveVersion(saved, nextVersion);
        log.info("更新 Agent id={} version={}", id, nextVersion);
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        AgentDefinitionEntity entity = load(id);
        entity.setDeleted(true);
        definitionRepository.save(entity);
        log.info("软删除 Agent id={}", id);
    }

    @Transactional
    public AgentResponse publish(Long id, Integer version) {
        AgentDefinitionEntity entity = load(id);
        int target = version == null ? entity.getCurrentVersion() : version;
        AgentVersionEntity versionEntity = versionRepository.findByAgentIdAndVersionNo(id, target)
                .orElseThrow(() -> BizException.notFound("Agent 版本不存在: " + target));
        versionEntity.setPublished(true);
        versionRepository.save(versionEntity);
        entity.setPublishedVersion(target);
        definitionRepository.save(entity);
        log.info("发布 Agent id={} version={}", id, target);
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public List<AgentVersionResponse> versions(Long id) {
        load(id);
        return versionRepository.findByAgentIdOrderByVersionNoDesc(id).stream()
                .map(v -> new AgentVersionResponse(v.getId(), v.getAgentId(), v.getVersionNo(),
                        v.getPublished(), v.getConfigJson(), v.getCreatedAt()))
                .toList();
    }

    /** 供对话/运行服务复用。 */
    public AgentDefinitionEntity load(Long id) {
        if (id == null) {
            throw BizException.badRequest("agentId 不能为空");
        }
        return definitionRepository.findByIdAndTenantIdAndDeletedFalse(id, TenantContext.getCurrentTenantId())
                .orElseThrow(() -> BizException.notFound("Agent 不存在: " + id));
    }

    private void saveVersion(AgentDefinitionEntity entity, int versionNo) {
        AgentVersionEntity version = new AgentVersionEntity();
        version.setTenantId(entity.getTenantId());
        version.setAgentId(entity.getId());
        version.setVersionNo(versionNo);
        version.setConfigJson(writeJson(toResponse(entity)));
        version.setPublished(false);
        versionRepository.save(version);
    }

    private void validateRequest(String type, List<String> tools, List<Long> subAgentIds) {
        AgentType agentType = AgentType.from(type);
        validateTools(tools);
        if (agentType.isFlow() && (subAgentIds == null || subAgentIds.isEmpty())) {
            throw BizException.badRequest(agentType.name() + " 类型至少需要一个 subAgentIds");
        }
    }

    private void validateTools(List<String> tools) {
        if (tools == null) {
            return;
        }
        List<String> missing = tools.stream().filter(tool -> !toolRegistry.exists(tool)).toList();
        if (!missing.isEmpty()) {
            throw BizException.badRequest("未注册的工具: " + missing + "，可用工具: " + toolRegistry.names());
        }
    }

    public AgentSummary toSummary(AgentDefinitionEntity entity) {
        return new AgentSummary(entity.getId(), entity.getName(), entity.getDescription(), entity.getType(),
                entity.getEnabled(), entity.getCurrentVersion(), entity.getPublishedVersion(),
                entity.getUpdatedAt());
    }

    public AgentResponse toResponse(AgentDefinitionEntity entity) {
        return new AgentResponse(entity.getId(), entity.getName(), entity.getDescription(), entity.getType(),
                entity.getSystemPrompt(), entity.getModelId(), entity.getTemperature(), entity.getMaxIterations(),
                readStringList(entity.getToolsJson()), readLongList(entity.getSubAgentIdsJson()),
                entity.getOutputType(), entity.getEnabled(), entity.getCurrentVersion(),
                entity.getPublishedVersion(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<Long> readLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> values = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {
                    });
            List<Long> ids = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof Number number) {
                    ids.add(number.longValue());
                } else if (value instanceof String text && !text.isBlank()) {
                    ids.add(Long.parseLong(text.trim()));
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(Objects.requireNonNullElse(value, List.of()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
