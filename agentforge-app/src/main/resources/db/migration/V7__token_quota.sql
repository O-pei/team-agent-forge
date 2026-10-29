-- AgentForge v0.2.0 V7: Token 额度控制（套餐 / 用户额度 / 手动分配记录 / 用量索引）
-- 方言：MySQL 8（H2 以 MODE=MySQL 兼容运行，禁止 MySQL 专有语法）
-- 归属：Lead 冻结的契约文件，其他人不要修改

CREATE TABLE ai_quota_tier (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    code              VARCHAR(32)  NOT NULL,
    name              VARCHAR(64)  NOT NULL,
    description       VARCHAR(255) NULL,
    daily_token_limit BIGINT       NOT NULL DEFAULT 0,
    total_token_limit BIGINT       NOT NULL DEFAULT 0,
    sort_order        INT          NOT NULL DEFAULT 0,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    builtin           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_quota_tier_code (code),
    KEY idx_ai_quota_tier_enabled (enabled, sort_order)
);

CREATE TABLE ai_user_quota (
    id                  BIGINT   NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT   NOT NULL DEFAULT 1,
    user_id             BIGINT   NOT NULL,
    tier_id             BIGINT   NULL,
    daily_limit_override BIGINT  NULL,
    total_limit_override BIGINT  NULL,
    granted_tokens      BIGINT   NOT NULL DEFAULT 0,
    created_at          DATETIME NOT NULL,
    updated_at          DATETIME NOT NULL,
    version             BIGINT   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_user_quota_user (user_id),
    KEY idx_ai_user_quota_tier (tier_id)
);

CREATE TABLE ai_quota_grant_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    user_id       BIGINT       NOT NULL,
    operator_id   BIGINT       NULL,
    amount        BIGINT       NOT NULL,
    reason        VARCHAR(255) NULL,
    balance_after BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_quota_grant_user (user_id, created_at)
);

-- 每日额度聚合按 (user_id, created_at) 查询，V2 只有 user_id 单列索引
CREATE INDEX idx_ai_token_usage_user_created ON ai_token_usage (user_id, created_at);

-- 内置套餐：0 表示不限。免费/专业档覆盖常见个人与团队使用，企业版默认不限。
INSERT INTO ai_quota_tier (code, name, description, daily_token_limit, total_token_limit,
                           sort_order, enabled, builtin, created_at, updated_at, version)
VALUES ('FREE', '体验版', '默认套餐：每日 2 万 token，总额度 20 万 token', 20000, 200000,
        10, TRUE, TRUE, NOW(), NOW(), 0),
       ('PRO', '专业版', '每日 20 万 token，总额度 500 万 token', 200000, 5000000,
        20, TRUE, TRUE, NOW(), NOW(), 0),
       ('ENTERPRISE', '企业版', '不限每日与总额度', 0, 0,
        30, TRUE, TRUE, NOW(), NOW(), 0);

-- 额度管理权限码
INSERT INTO af_permission (code, name, resource, action, created_at, updated_at, version)
VALUES ('quota:read', '查看 Token 额度', 'quota', 'read', NOW(), NOW(), 0),
       ('quota:write', '管理 Token 额度', 'quota', 'write', NOW(), NOW(), 0);

-- SUPER_ADMIN 与 ADMIN 均可查看和分配额度；USER 通过 /quota/me 查看自己的额度
INSERT INTO af_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM af_role r
         JOIN af_permission p ON p.code IN ('quota:read', 'quota:write')
WHERE r.code = 'SUPER_ADMIN';

INSERT INTO af_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM af_role r
         JOIN af_permission p ON p.code IN ('quota:read', 'quota:write')
WHERE r.code = 'ADMIN';
