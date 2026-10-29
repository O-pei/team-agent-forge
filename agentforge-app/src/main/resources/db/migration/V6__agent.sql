-- AgentForge V6: 多智能体 Harness（Agent 定义 / 版本 / 运行 / 步骤 / 编排）
-- 方言：MySQL 8（H2 以 MODE=MySQL 兼容运行）
-- 约定：前缀 agent_，字段 snake_case；除日志表外均含 created_at/updated_at/version（乐观锁）。

CREATE TABLE agent_definition (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL DEFAULT 1,
    name                VARCHAR(64)  NOT NULL,
    description         VARCHAR(512) NULL,
    type                VARCHAR(16)  NOT NULL DEFAULT 'SINGLE',
    system_prompt       TEXT         NULL,
    model_id            BIGINT       NULL,
    temperature         DOUBLE       NULL,
    max_iterations      INT          NOT NULL DEFAULT 5,
    tools_json          TEXT         NULL,
    sub_agent_ids_json  TEXT         NULL,
    output_type         VARCHAR(255) NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    current_version     INT          NOT NULL DEFAULT 1,
    published_version   INT          NULL,
    deleted             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_definition_name (tenant_id, name),
    KEY idx_agent_definition_tenant (tenant_id)
);

CREATE TABLE agent_version (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL DEFAULT 1,
    agent_id      BIGINT      NOT NULL,
    version_no    INT         NOT NULL,
    config_json   TEXT        NOT NULL,
    published     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    DATETIME    NOT NULL,
    updated_at    DATETIME    NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_version_agent_no (agent_id, version_no),
    KEY idx_agent_version_agent (agent_id)
);

CREATE TABLE agent_run (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT        NOT NULL DEFAULT 1,
    agent_id          BIGINT        NOT NULL,
    agent_version     INT           NULL,
    session_id        VARCHAR(64)   NULL,
    user_id           BIGINT        NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    input             TEXT          NULL,
    output            TEXT          NULL,
    prompt_tokens     BIGINT        NOT NULL DEFAULT 0,
    completion_tokens BIGINT        NOT NULL DEFAULT 0,
    total_tokens      BIGINT        NOT NULL DEFAULT 0,
    model_id          BIGINT        NULL,
    model_name        VARCHAR(128)  NULL,
    error_message     VARCHAR(1024) NULL,
    trace_id          VARCHAR(64)   NULL,
    started_at        DATETIME      NULL,
    finished_at       DATETIME      NULL,
    created_at        DATETIME      NOT NULL,
    updated_at        DATETIME      NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_agent_run_agent (agent_id, id),
    KEY idx_agent_run_session (session_id),
    KEY idx_agent_run_tenant (tenant_id, id)
);

CREATE TABLE agent_run_step (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    run_id      BIGINT       NOT NULL,
    seq_no      INT          NOT NULL DEFAULT 0,
    agent_name  VARCHAR(64)  NULL,
    node_name   VARCHAR(64)  NULL,
    step_type   VARCHAR(16)  NOT NULL,
    tool_name   VARCHAR(64)  NULL,
    input       TEXT         NULL,
    output      TEXT         NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'SUCCEEDED',
    elapsed_ms  BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_agent_run_step_run (run_id, seq_no)
);

CREATE TABLE agent_session (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL DEFAULT 1,
    agent_id       BIGINT       NOT NULL,
    session_id     VARCHAR(64)  NOT NULL,
    user_id        BIGINT       NULL,
    title          VARCHAR(255) NULL,
    message_count  INT          NOT NULL DEFAULT 0,
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_session_session_id (session_id),
    KEY idx_agent_session_agent (agent_id, id)
);

CREATE TABLE agent_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL DEFAULT 1,
    agent_id        BIGINT       NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    run_id          BIGINT       NULL,
    role            VARCHAR(16)  NOT NULL,
    content         TEXT         NULL,
    tool_calls_json TEXT         NULL,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_agent_message_session (session_id, id)
);

CREATE TABLE agent_orchestration (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL DEFAULT 1,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(512) NULL,
    type        VARCHAR(16)  NOT NULL,
    config_json TEXT         NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_orchestration_name (tenant_id, name),
    KEY idx_agent_orchestration_tenant (tenant_id)
);
