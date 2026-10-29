package com.agentforge.agent.service;

import com.agentforge.agent.dto.OrchestrationRequest;
import com.agentforge.agent.dto.OrchestrationResponse;
import com.agentforge.agent.entity.AgentOrchestrationEntity;
import com.agentforge.agent.repository.AgentOrchestrationRepository;
import com.agentforge.common.context.TenantContext;
import com.agentforge.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 编排配置 CRUD。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationService {

    private static final Set<String> TYPES = Set.of("SEQUENTIAL", "PARALLEL", "ROUTING", "SUPERVISOR");

    private final AgentOrchestrationRepository repository;

    @Transactional(readOnly = true)
    public List<OrchestrationResponse> list() {
        return repository.findByTenantIdOrderByIdDesc(TenantContext.getCurrentTenantId(), PageRequest.of(0, 200)).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public OrchestrationResponse get(Long id) {
        return toResponse(load(id));
    }

    @Transactional
    public OrchestrationResponse create(OrchestrationRequest request) {
        validate(request);
        Long tenantId = TenantContext.getCurrentTenantId();
        if (repository.existsByTenantIdAndName(tenantId, request.name().trim())) {
            throw BizException.conflict("编排名称已存在: " + request.name());
        }
        AgentOrchestrationEntity entity = new AgentOrchestrationEntity();
        entity.setTenantId(tenantId);
        entity.setName(request.name().trim());
        entity.setDescription(request.description());
        entity.setType(request.type().trim().toUpperCase());
        entity.setConfigJson(request.configJson());
        entity.setEnabled(request.enabled() == null || request.enabled());
        return toResponse(repository.save(entity));
    }

    @Transactional
    public OrchestrationResponse update(Long id, OrchestrationRequest request) {
        AgentOrchestrationEntity entity = load(id);
        validate(request);
        if (!entity.getName().equals(request.name().trim())
                && repository.existsByTenantIdAndName(TenantContext.getCurrentTenantId(), request.name().trim())) {
            throw BizException.conflict("编排名称已存在: " + request.name());
        }
        entity.setName(request.name().trim());
        entity.setDescription(request.description());
        entity.setType(request.type().trim().toUpperCase());
        entity.setConfigJson(request.configJson());
        entity.setEnabled(request.enabled() == null || request.enabled());
        return toResponse(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(load(id));
    }

    private AgentOrchestrationEntity load(Long id) {
        return repository.findByIdAndTenantId(id, TenantContext.getCurrentTenantId())
                .orElseThrow(() -> BizException.notFound("编排不存在: " + id));
    }

    private void validate(OrchestrationRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw BizException.badRequest("name 不能为空");
        }
        if (request.type() == null || !TYPES.contains(request.type().trim().toUpperCase())) {
            throw BizException.badRequest("type 必须是 " + TYPES);
        }
    }

    private OrchestrationResponse toResponse(AgentOrchestrationEntity entity) {
        return new OrchestrationResponse(entity.getId(), entity.getName(), entity.getDescription(),
                entity.getType(), entity.getConfigJson(), entity.getEnabled(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
