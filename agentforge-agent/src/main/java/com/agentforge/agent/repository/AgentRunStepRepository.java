package com.agentforge.agent.repository;

import com.agentforge.agent.entity.AgentRunStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunStepRepository extends JpaRepository<AgentRunStepEntity, Long> {

    List<AgentRunStepEntity> findByRunIdOrderBySeqNoAscIdAsc(Long runId);

    void deleteByRunId(Long runId);
}
