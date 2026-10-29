package com.agentforge.agent.repository;

import com.agentforge.agent.entity.AgentMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, Long> {

    List<AgentMessageEntity> findBySessionIdOrderByIdAsc(String sessionId);
}
