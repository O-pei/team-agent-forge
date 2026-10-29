package com.agentforge.agent.repository;

import com.agentforge.agent.entity.AgentSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity, Long> {

    Optional<AgentSessionEntity> findBySessionId(String sessionId);
}
