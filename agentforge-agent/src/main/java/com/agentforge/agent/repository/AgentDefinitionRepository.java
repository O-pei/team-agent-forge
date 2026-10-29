package com.agentforge.agent.repository;

import com.agentforge.agent.entity.AgentDefinitionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinitionEntity, Long> {

    Page<AgentDefinitionEntity> findByTenantIdAndDeletedFalse(Long tenantId, Pageable pageable);

    Page<AgentDefinitionEntity> findByTenantIdAndDeletedFalseAndNameContainingIgnoreCase(
            Long tenantId, String name, Pageable pageable);

    Optional<AgentDefinitionEntity> findByIdAndTenantIdAndDeletedFalse(Long id, Long tenantId);

    boolean existsByTenantIdAndNameAndDeletedFalse(Long tenantId, String name);

    List<AgentDefinitionEntity> findByIdInAndTenantIdAndDeletedFalse(Collection<Long> ids, Long tenantId);
}
