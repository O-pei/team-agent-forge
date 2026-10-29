package com.agentforge.agent.repository;

import com.agentforge.agent.entity.AgentVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentVersionRepository extends JpaRepository<AgentVersionEntity, Long> {

    List<AgentVersionEntity> findByAgentIdOrderByVersionNoDesc(Long agentId);

    Optional<AgentVersionEntity> findByAgentIdAndVersionNo(Long agentId, Integer versionNo);

    List<AgentVersionEntity> findByAgentIdAndPublishedTrue(Long agentId);
}
