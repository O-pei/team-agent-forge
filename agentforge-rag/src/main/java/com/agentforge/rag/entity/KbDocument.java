package com.agentforge.rag.entity;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库文档（kb_document）。异步解析：PENDING -> PROCESSING -> READY / FAILED。
 */
@Getter
@Setter
@Entity
@Table(name = "kb_document")
public class KbDocument extends BaseEntity {

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long kbId;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(length = 128)
    private String contentType;

    @Column(nullable = false)
    private Long fileSize = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(nullable = false)
    private Integer chunkCount = 0;

    @Column(length = 1000)
    private String errorMessage;

    /** 上传原文落盘路径（用于 reindex 重新解析）。 */
    @Column(length = 512)
    private String storagePath;

    @PrePersist
    protected void fillDefaults() {
        if (tenantId == null) {
            tenantId = 1L;
        }
        if (fileSize == null) {
            fileSize = 0L;
        }
        if (chunkCount == null) {
            chunkCount = 0;
        }
        if (status == null) {
            status = DocumentStatus.PENDING;
        }
    }
}
