-- AgentForge v0.1.0 baseline: 认证与 RBAC
-- 方言：MySQL 8（H2 以 MODE=MySQL 兼容运行，开发期不得使用 MySQL 专有语法以外的特性）

CREATE TABLE af_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    username      VARCHAR(64)  NOT NULL,
    email         VARCHAR(128) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    nickname      VARCHAR(64)  NULL,
    avatar_url    VARCHAR(512) NULL,
    phone         VARCHAR(32)  NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    email_verified BOOLEAN     NOT NULL DEFAULT FALSE,
    last_login_at DATETIME     NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_user_username (username),
    UNIQUE KEY uk_af_user_email (email),
    KEY idx_af_user_tenant (tenant_id)
);

CREATE TABLE af_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL DEFAULT 1,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) NULL,
    builtin     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_role_code (tenant_id, code)
);

CREATE TABLE af_permission (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(128) NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    resource    VARCHAR(64)  NOT NULL,
    action      VARCHAR(32)  NOT NULL,
    description VARCHAR(255) NULL,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_permission_code (code)
);

CREATE TABLE af_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_af_user_role_user FOREIGN KEY (user_id) REFERENCES af_user (id),
    CONSTRAINT fk_af_user_role_role FOREIGN KEY (role_id) REFERENCES af_role (id)
);

CREATE TABLE af_role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_af_role_perm_role FOREIGN KEY (role_id) REFERENCES af_role (id),
    CONSTRAINT fk_af_role_perm_perm FOREIGN KEY (permission_id) REFERENCES af_permission (id)
);

CREATE TABLE af_email_verification (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    email      VARCHAR(128) NOT NULL,
    code_hash  VARCHAR(128) NOT NULL,
    scene      VARCHAR(32)  NOT NULL,
    expires_at DATETIME     NOT NULL,
    consumed   BOOLEAN      NOT NULL DEFAULT FALSE,
    attempts   INT          NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_af_email_code (email, scene, consumed)
);

CREATE TABLE af_refresh_token (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    expires_at DATETIME     NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    user_agent VARCHAR(255) NULL,
    ip         VARCHAR(64)  NULL,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_refresh_token (token_hash),
    KEY idx_af_refresh_user (user_id)
);

CREATE TABLE af_login_log (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NULL,
    username   VARCHAR(64) NOT NULL,
    success    BOOLEAN     NOT NULL,
    reason     VARCHAR(255) NULL,
    ip         VARCHAR(64) NULL,
    user_agent VARCHAR(255) NULL,
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_af_login_log_user (user_id, created_at)
);

-- 内置角色与权限种子数据
INSERT INTO af_role (tenant_id, code, name, description, builtin, created_at, updated_at, version)
VALUES (1, 'SUPER_ADMIN', '超级管理员', '拥有全部权限', TRUE, NOW(), NOW(), 0),
       (1, 'ADMIN', '管理员', '除系统级配置外的管理权限', TRUE, NOW(), NOW(), 0),
       (1, 'USER', '普通用户', '使用 Agent / 知识库 / 会话', TRUE, NOW(), NOW(), 0);

INSERT INTO af_permission (code, name, resource, action, created_at, updated_at, version)
VALUES ('user:read', '查看用户', 'user', 'read', NOW(), NOW(), 0),
       ('user:write', '管理用户', 'user', 'write', NOW(), NOW(), 0),
       ('role:read', '查看角色', 'role', 'read', NOW(), NOW(), 0),
       ('role:write', '管理角色', 'role', 'write', NOW(), NOW(), 0),
       ('audit:read', '查看审计', 'audit', 'read', NOW(), NOW(), 0),
       ('agent:read', '查看 Agent', 'agent', 'read', NOW(), NOW(), 0),
       ('agent:write', '管理 Agent', 'agent', 'write', NOW(), NOW(), 0),
       ('agent:run', '运行 Agent', 'agent', 'run', NOW(), NOW(), 0),
       ('rag:read', '查看知识库', 'rag', 'read', NOW(), NOW(), 0),
       ('rag:write', '管理知识库', 'rag', 'write', NOW(), NOW(), 0),
       ('datasource:read', '查看数据源', 'datasource', 'read', NOW(), NOW(), 0),
       ('datasource:write', '管理数据源', 'datasource', 'write', NOW(), NOW(), 0),
       ('datasource:query', '执行数据源查询', 'datasource', 'query', NOW(), NOW(), 0),
       ('memory:read', '查看记忆', 'memory', 'read', NOW(), NOW(), 0),
       ('memory:write', '管理记忆', 'memory', 'write', NOW(), NOW(), 0);

INSERT INTO af_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM af_role r CROSS JOIN af_permission p WHERE r.code = 'SUPER_ADMIN';

INSERT INTO af_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM af_role r JOIN af_permission p
  ON p.code IN ('user:read', 'role:read', 'audit:read', 'agent:read', 'agent:write', 'agent:run',
                'rag:read', 'rag:write', 'datasource:read', 'datasource:write', 'datasource:query',
                'memory:read', 'memory:write')
WHERE r.code = 'ADMIN';

INSERT INTO af_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM af_role r JOIN af_permission p
  ON p.code IN ('agent:read', 'agent:run', 'rag:read', 'datasource:read', 'datasource:query', 'memory:read')
WHERE r.code = 'USER';
