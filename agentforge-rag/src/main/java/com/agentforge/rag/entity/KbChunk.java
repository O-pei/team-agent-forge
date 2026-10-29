package com.agentforge.rag.entity;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 鏂囨。鍒嗗潡锛坘b_chunk锛夈€? */
@Getter
@Setter
@Entity
@Table(name = "kb_chunk")
public class KbChunk extends BaseEntity {

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private Long kbId;

    @Column(nullable = false)
    private Integer chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Integer charCount = 0;

    @PrePersist
    protected void fillDefaults() {
        if (tenantId == null) {
            tenantId = 1L;
        }
        if (charCount == null) {
            charCount = content == null ? 0 : content.length();
        }
    }
}


