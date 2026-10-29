-- AgentForge v0.1.0 V2: 模型配置 + Token 用量审计 + 模型单价
-- 方言：MySQL 8（H2 以 MODE=MySQL 兼容运行，禁止 MySQL 专有语法）
-- 归属：T2 模型网关 + Token 审计（写入者：audit-memory）

CREATE TABLE ai_model (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    name              VARCHAR(64)   NOT NULL,
    provider          VARCHAR(32)   NOT NULL,
    base_url          VARCHAR(512)  NULL,
    model_name        VARCHAR(128)  NOT NULL,
    api_key_encrypted VARCHAR(1024) NULL,
    temperature       DOUBLE        NULL,
    max_tokens        INT           NULL,
    is_default        BOOLEAN       NOT NULL DEFAULT FALSE,
    enabled           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        DATETIME      NOT NULL,
    updated_at        DATETIME      NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_model_name (name),
    KEY idx_ai_model_provider (provider, model_name),
    KEY idx_ai_model_default (is_default, enabled)
);

CREATE TABLE ai_token_usage (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT        NOT NULL DEFAULT 1,
    user_id           BIGINT        NULL,
    agent_id          BIGINT        NULL,
    run_id            BIGINT        NULL,
    session_id        VARCHAR(64)   NULL,
    model_id          BIGINT        NULL,
    model_name        VARCHAR(128)  NULL,
    provider          VARCHAR(32)   NULL,
    prompt_tokens     BIGINT        NOT NULL DEFAULT 0,
    completion_tokens BIGINT        NOT NULL DEFAULT 0,
    total_tokens      BIGINT        NOT NULL DEFAULT 0,
    cost              DECIMAL(18,6) NOT NULL DEFAULT 0,
    stream            BOOLEAN       NOT NULL DEFAULT FALSE,
    success           BOOLEAN       NOT NULL DEFAULT TRUE,
    latency_ms        BIGINT        NOT NULL DEFAULT 0,
    trace_id          VARCHAR(64)   NULL,
    created_at        DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_token_usage_created (created_at),
    KEY idx_ai_token_usage_tenant (tenant_id, created_at),
    KEY idx_ai_token_usage_model (model_id),
    KEY idx_ai_token_usage_agent (agent_id),
    KEY idx_ai_token_usage_user (user_id),
    KEY idx_ai_token_usage_trace (trace_id)
);

CREATE TABLE ai_model_price (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    provider           VARCHAR(32)   NOT NULL,
    model_name         VARCHAR(128)  NOT NULL,
    input_price_per_1k DECIMAL(18,6) NOT NULL DEFAULT 0,
    output_price_per_1k DECIMAL(18,6) NOT NULL DEFAULT 0,
    currency           VARCHAR(8)    NOT NULL DEFAULT 'CNY',
    created_at         DATETIME      NOT NULL,
    updated_at         DATETIME      NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_model_price (provider, model_name)
);

-- 零配置可跑：默认 MOCK 模型
INSERT INTO ai_model (name, provider, base_url, model_name, api_key_encrypted, temperature, max_tokens,
                      is_default, enabled, created_at, updated_at, version)
VALUES ('Mock 默认模型', 'MOCK', NULL, 'mock-chat', NULL, 0.7, 2048, TRUE, TRUE, NOW(), NOW(), 0);

-- 默认单价（每 1K token，人民币）：MOCK 免费；qwen-plus 参考阿里云价格，后续可在管理端调整
INSERT INTO ai_model_price (provider, model_name, input_price_per_1k, output_price_per_1k, currency,
                            created_at, updated_at, version)
VALUES ('MOCK', 'mock-chat', 0.000000, 0.000000, 'CNY', NOW(), NOW(), 0),
       ('DASHSCOPE', 'qwen-plus', 0.004000, 0.012000, 'CNY', NOW(), NOW(), 0);

-- 模型管理权限码（V1 未包含）
INSERT INTO af_permission (code, name, resource, action, created_at, updated_at, version)
VALUES ('model:read', '查看模型配置', 'model', 'read', NOW(), NOW(), 0),
       ('model:write', '管理模型配置', 'model', 'write', NOW(), NOW(), 0);

-- SUPER_ADMIN 拥有 model:read/write；ADMIN 只有 model:read；USER 不授予
INSERT INTO af_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM af_role r
         JOIN af_permission p ON p.code IN ('model:read', 'model:write')
WHERE r.code = 'SUPER_ADMIN';

INSERT INTO af_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM af_role r
         JOIN af_permission p ON p.code = 'model:read'
WHERE r.code = 'ADMIN';
