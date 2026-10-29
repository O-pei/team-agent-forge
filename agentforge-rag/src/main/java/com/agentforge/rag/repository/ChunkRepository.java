package com.agentforge.rag.repository;

import com.agentforge.rag.entity.KbChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 分块仓储。
 */
public interface ChunkRepository extends JpaRepository<KbChunk, Long> {

    List<KbChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    List<KbChunk> findByKbIdOrderByDocumentIdAscChunkIndexAsc(Long kbId);

    long countByKbId(Long kbId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from KbChunk c where c.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from KbChunk c where c.kbId = :kbId")
    int deleteByKbId(@Param("kbId") Long kbId);
}
