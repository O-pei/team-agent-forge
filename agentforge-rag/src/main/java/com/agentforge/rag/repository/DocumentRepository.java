package com.agentforge.rag.repository;

import com.agentforge.rag.entity.DocumentStatus;
import com.agentforge.rag.entity.KbDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 文档仓储。
 */
public interface DocumentRepository extends JpaRepository<KbDocument, Long> {

    Page<KbDocument> findByKbId(Long kbId, Pageable pageable);

    Page<KbDocument> findByKbIdAndStatus(Long kbId, DocumentStatus status, Pageable pageable);

    List<KbDocument> findByKbId(Long kbId);

    long countByKbId(Long kbId);
}
