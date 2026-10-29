package com.agentforge.agent.repository;

import com.agentforge.agent.entity.AgentOrchestrationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentOrchestrationRepository extends JpaRepository<AgentOrchestrationEntity, Long> {

    Page<AgentOrchestrationEntity> findByTenantIdOrderByIdDesc(Long tenantId, Pageable pageable);

    Optional<AgentOrchestrationEntity> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByTenantIdAndName(Long tenantId, String name);
}
