# 安全策略（Security Policy）

AgentForge 处理账号、模型密钥、企业数据源与代码执行，安全是发布门槛。本文说明版本支持、漏洞上报方式、安全设计要点与生产部署红线。

## 1. 支持的版本

| 版本 | 状态 | 安全更新 |
| --- | --- | --- |
| `0.1.x` | 当前开发版本（首发） | ✅ 接收并修复安全问题 |
| `< 0.1` | 不存在的早期快照 | ❌ 不支持 |

> 升级前请阅读 [`CHANGELOG.md`](CHANGELOG.md)。安全修复会优先进入 `main` 并发布补丁版本。

## 2. 漏洞上报

**请不要通过公开 Issue / PR / 讨论区披露安全漏洞。**

- 联系邮箱：`security@example.com`（**占位地址，项目维护者发布前必须替换为真实安全邮箱**）。
- 建议在邮件标题注明 `[SECURITY]`，内容包含：
  - 受影响的版本 / 提交哈希与部署方式（profile、依赖服务）；
  - 漏洞类型与影响范围（认证绕过、越权、SQL 注入、沙箱逃逸、密钥泄漏等）；
  - 最小复现步骤或 PoC（可脱敏，但请保证可复现）；
  - 是否已公开、期望的修复时限。
- 维护者目标：**3 个工作日内确认收到**，7 个工作日内给出初步评估与修复计划；修复发布后再与你协调公开披露。
- 请勿在漏洞修复前公开细节，也不要访问、修改或删除非你本人账号/租户的数据。

## 3. 安全设计要点

| 领域 | 机制 | 位置 |
| --- | --- | --- |
| 认证（无状态） | HS256 JWT，`Authorization: Bearer`；access 2h、refresh 7d；access 携带 `userId/tenantId/roles/perms/jti`，`jti` 登出后进入黑名单 | `agentforge-security` 的 `JwtService` / `JwtAuthenticationFilter` / `TokenBlacklist` |
| 密码存储 | BCrypt（强度 10），不存明文、不可逆；管理员重置与找回密码同样走 BCrypt | `SecurityConfig#passwordEncoder`、`AuthService` / `UserService` |
| 刷新令牌 | refresh 为 48 字节随机值，**只存 SHA-256 哈希**（带服务端 pepper），一次性使用（轮换）；登出/改密吊销全部 refresh | `RefreshTokenService`、`af_refresh_token` |
| 邮箱验证码 | 6 位数字，5 分钟有效；60s 重发冷却、单邮箱 1h ≤10 次、单 IP 1h ≤20 次；错误 5 次作废；验码常量时间比较 | `EmailCodeService`、`af_email_verification` |
| 登录防暴力 | 同一账号 15 分钟内连续失败 5 次即锁定（错误码 40103），成功登录清零；失败/成功均写 `af_login_log` | `LoginAttemptService`、`AuthService` |
| 鉴权 | SecurityFilterChain 无状态；匿名白名单之外一律 `authenticated()`；方法级 `@PreAuthorize("hasAuthority('<permission>')")`；权限码来自 DB | `SecurityConfig`、各模块 Controller |
| 401/403 | 统一返回 `ApiResponse` JSON（40100/40101/40102/40103/40104、40300），不返回 HTML/空 body；业务码在附录 B | `JsonAuthenticationEntryPoint`、`JsonAccessDeniedHandler`、`GlobalExceptionHandler` |
| 敏感字段加密 | 模型 API Key、数据源密码等入库前 AES-256-GCM 加密（`v1:` + Base64(12B IV + 密文)），密钥由口令 SHA-256 派生 | `com.agentforge.common.crypto.CryptoUtils`、`ai_model.api_key_encrypted`、`ds_datasource` |
| SQL 只读校验 | 仅允许单条 `SELECT`：拒绝注释/多语句/UNION/DDL/DML/加锁查询与危险函数；强制 `LIMIT`（默认 100、上限 500）；查询超时 10s；只读连接执行并记录 `ds_query_log` | `agentforge-datasource` 的 `SqlGuard` / `SqlQueryService` |
| 代码沙箱 | `ProcessSandboxProvider`：语言白名单（java/python/node/shell）、代码 ≤100KB、独立临时目录执行后删除、子进程环境变量白名单重建（不继承父进程 API Key 等）、超时（默认 10s，上限 30s）杀进程树、stdout/stderr 各 64KB 截断 | `agentforge-datasource` 的 `ProcessSandboxProvider` |
| 审计只读 | 审计/用量/登录日志接口只提供查询（`audit:read`），不提供篡改入口；Token 用量落 `ai_token_usage`，SQL 执行落 `ds_query_log` | `AuditController`、`LoginLogController` |
| 日志与追踪 | 每个请求生成 `traceId`（`X-Trace-Id`）贯穿日志与响应；**禁止打印密钥、Token、密码、完整 SQL 参数** | `TraceIdFilter`、`ApiResponse.traceId` |
| 配置隔离 | 所有密钥走环境变量；仓库内只有 dev 占位默认值，生产必须覆盖 | `application.yml` |

## 4. 生产部署红线

发布前逐条确认，**任意一条不满足都不得上线**：

- [ ] **替换 JWT 密钥**：`AGENTFORGE_JWT_SECRET` ≥ 32 字节且随机（默认值仅供本地开发）；更换后所有旧 Token 失效。
- [ ] **替换加密主密钥**：`AGENTFORGE_CRYPTO_KEY` 使用独立随机值；更换后旧密文不可解密，需先迁移或重新录入敏感配置。
- [ ] **关闭 dev 种子管理员**：`AGENTFORGE_SEED_ADMIN=false`，并修改/删除 dev 默认账号 `admin@agentforge.dev`；`AGENTFORGE_ADMIN_PASSWORD` 不得使用默认值。
- [ ] **关闭验证码回显**：`AGENTFORGE_EXPOSE_CODE=false`（`agentforge.auth.expose-code-in-response`），仅本地演示可临时打开。
- [ ] **启用真实邮件通道**：配置 `spring.mail.*` 与 `AGENTFORGE_MAIL_ENABLED=true`，避免验证码只出现在日志中（日志兜底仅限开发）。
- [ ] **HTTPS**：在网关/反向代理终止 TLS，强制 HSTS；不要明文暴露 8080。同时正确设置 CORS 允许来源，避免 `*` 与凭据共用。
- [ ] **关闭调试入口**：生产禁用 `dev` profile（无 H2 Console）；关闭 Swagger/OpenAPI（`springdoc.api-docs.enabled=false` 并在网关屏蔽 `/swagger-ui/**`、`/v3/api-docs/**`）。
- [ ] **数据库最小权限**：应用账号只授予业务库 DML/DDL（迁移）所需权限；数据源只读账号使用只读账号，禁止 SUPER/DDL。
- [ ] **密钥管理**：使用 KMS/Vault/CI Secret，不要把 `.env`、密钥写进镜像或仓库；轮换流程需可回滚。
- [ ] **多实例部署**：Token 黑名单、登录锁定、验证码限流目前是进程内实现，多实例需接入 Redis 或网关限流（见第 5 节）。
- [ ] **沙箱边界**：`ProcessSandbox` 只是进程隔离，不是安全边界；面向不可信用户执行代码时必须使用 Docker/容器强隔离（`DockerSandboxProvider`）并限制网络与挂载。
- [ ] **依赖与漏洞扫描**：上线前跑 `mvn -B verify` 与依赖漏洞扫描（CVE），关注 Spring Boot/Spring Security/JSqlParser 安全公告。

## 5. 已知限制与后续加固

1. **进程内安全状态**：`TokenBlacklist`（access 注销黑名单）、`LoginAttemptService`（失败锁定）、`InMemoryRateLimiter`（验证码 IP 限流）均为单实例内存实现；多实例部署时登出与锁定不跨节点生效，必须替换为 Redis（实现同一接口即可）。
2. **refresh token 历史行**：轮换/吊销只置 `revoked=true`，暂无定期清理任务；建议生产加定时任务清理过期行。
3. **数据权限**：v0.1.0 仅实现功能权限（`@PreAuthorize` + 权限码），行/部门级数据权限尚未实现。
4. **无 MFA**：暂不支持 TOTP/WebAuthn，短信登录为预留扩展点（返回 `50300 sms-not-configured`）。
5. **开发默认值**：`application.yml` 中 JWT/Crypto 默认值、dev 管理员密码、`expose-code-in-response` 均为开发便利，生产必须覆盖（见第 4 节）。
6. **审计接口**：当前仅记录 Token 用量、登录日志与数据源查询日志；更完整的操作审计（谁在何时改了哪个配置）需在后续版本补齐。
7. **CI**：`.github/workflows/backend-ci.yml` 执行 `mvn -B -ntp verify` 与前端 `pnpm build`；密钥扫描（gitleaks 等）建议作为发布前必跑项，目前尚未接入 CI。

## 6. 安全相关依赖版本

关键安全组件版本由根 `pom.xml` 统一管理：Spring Boot 3.5.16 / Spring Security 6、jjwt 0.12.6、JSqlParser、H2（仅 dev）、MySQL Connector/J。升级这些依赖时请在 PR 中说明安全影响并跑全量测试。

---

发现安全问题请按第 2 节私下上报；感谢你帮助 AgentForge 变得更安全。
