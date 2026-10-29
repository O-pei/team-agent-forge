-- AgentForge v0.1.0: RAG 知识库 / 文档 / 分块
-- 方言：MySQL 8（H2 以 MODE=MySQL 兼容运行；避免 MySQL 专有语法）
-- 表前缀：kb_（见 docs/04-API契约.md §5）

CREATE TABLE kb_knowledge_base (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT       NOT NULL DEFAULT 1,
    name               VARCHAR(128) NOT NULL,
    description        VARCHAR(512) NULL,
    embedding_model_id BIGINT       NULL,
    embedding_dimension INT         NOT NULL DEFAULT 1024,
    document_count     INT          NOT NULL DEFAULT 0,
    chunk_count        INT          NOT NULL DEFAULT 0,
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_kb_knowledge_base_tenant (tenant_id),
    KEY idx_kb_knowledge_base_name (name)
);

CREATE TABLE kb_document (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    kb_id         BIGINT       NOT NULL,
    filename      VARCHAR(255) NOT NULL,
    content_type  VARCHAR(128) NULL,
    file_size     BIGINT       NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    chunk_count   INT          NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    storage_path  VARCHAR(512) NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_kb_document_kb (kb_id, status),
    KEY idx_kb_document_created (created_at)
);

CREATE TABLE kb_chunk (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL DEFAULT 1,
    document_id BIGINT      NOT NULL,
    kb_id       BIGINT      NOT NULL,
    chunk_index INT         NOT NULL,
    content     TEXT        NOT NULL,
    char_count  INT         NOT NULL DEFAULT 0,
    created_at  DATETIME    NOT NULL,
    updated_at  DATETIME    NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_kb_chunk_document (document_id, chunk_index),
    KEY idx_kb_chunk_kb (kb_id)
);
