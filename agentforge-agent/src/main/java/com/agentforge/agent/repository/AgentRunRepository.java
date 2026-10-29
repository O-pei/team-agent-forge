package com.agentforge.agent.repository;

import com.agentforge.agent.entity.AgentRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {

    Page<AgentRunEntity> findByTenantIdOrderByIdDesc(Long tenantId, Pageable pageable);

    Page<AgentRunEntity> findByTenantIdAndAgentIdOrderByIdDesc(Long tenantId, Long agentId, Pageable pageable);

    Optional<AgentRunEntity> findByIdAndTenantId(Long id, Long tenantId);
}
