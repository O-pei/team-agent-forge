-- AgentForge v0.1.0: 数据源直连（只读查询）与查询审计
-- 方言：MySQL 8（H2 以 MODE=MySQL 兼容运行）
-- 表前缀：ds_（见 docs/04-API契约.md §5）；日志表不建外键

CREATE TABLE ds_datasource (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT        NOT NULL DEFAULT 1,
    name               VARCHAR(128)  NOT NULL,
    type               VARCHAR(16)   NOT NULL,
    jdbc_url           VARCHAR(1024) NOT NULL,
    username           VARCHAR(128)  NOT NULL,
    password_encrypted VARCHAR(1024) NOT NULL,
    read_only          BOOLEAN       NOT NULL DEFAULT TRUE,
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE,
    max_pool_size      INT           NOT NULL DEFAULT 5,
    created_at         DATETIME      NOT NULL,
    updated_at         DATETIME      NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_ds_datasource_tenant (tenant_id),
    KEY idx_ds_datasource_name (name)
);

CREATE TABLE ds_query_log (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT        NOT NULL DEFAULT 1,
    datasource_id BIGINT        NOT NULL,
    user_id       BIGINT        NULL,
    sql_text      TEXT          NOT NULL,
    success       BOOLEAN       NOT NULL,
    row_count     INT           NOT NULL DEFAULT 0,
    elapsed_ms    BIGINT        NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    created_at    DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ds_query_log_ds (datasource_id, created_at)
);
