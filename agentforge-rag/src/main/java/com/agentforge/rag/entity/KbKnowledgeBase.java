package com.agentforge.rag.entity;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库（kb_knowledge_base）。
 */
@Getter
@Setter
@Entity
@Table(name = "kb_knowledge_base")
public class KbKnowledgeBase extends BaseEntity {

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /** 关联的模型配置 id（v0.1 允许为空，默认使用内置 HashingEmbeddingModel）。 */
    private Long embeddingModelId;

    /** 向量维度，便于后续切换 embedding 模型时校验。 */
    @Column(nullable = false)
    private Integer embeddingDimension = 1024;

    @Column(nullable = false)
    private Integer documentCount = 0;

    @Column(nullable = false)
    private Integer chunkCount = 0;

    @PrePersist
    protected void fillDefaults() {
        if (tenantId == null) {
            tenantId = 1L;
        }
        if (embeddingDimension == null) {
            embeddingDimension = 1024;
        }
        if (documentCount == null) {
            documentCount = 0;
        }
        if (chunkCount == null) {
            chunkCount = 0;
        }
    }
}
