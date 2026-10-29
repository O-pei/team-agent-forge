package com.agentforge.rag.repository;

import com.agentforge.rag.entity.KbKnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识库仓储。
 */
public interface KnowledgeBaseRepository extends JpaRepository<KbKnowledgeBase, Long> {
}
