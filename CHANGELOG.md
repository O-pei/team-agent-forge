# Changelog

本文档格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

暂无（欢迎通过 Issue/PR 贡献）。

## [0.2.0] - 2026-10-30

本版本聚焦「企业可治理」的最后一块拼图：Token 额度控制。管理员可以按套餐档位、按用户覆盖、按需手动追加三种方式分配额度，超额请求在进入 SSE/模型调用前即被拦截（HTTP 429 / code 42901），运行中超额会停止后续模型调用并保留已生成内容。

### Added

- **额度套餐**：`ai_quota_tier` 表与 FREE / PRO / ENTERPRISE 内置套餐；`GET/POST/PUT/DELETE /api/v1/quota/tiers`，支持自建档位、启停、排序与使用人数统计，内置套餐或被引用时删除返回 409。
- **用户额度**：`ai_user_quota` 表；`GET /api/v1/quota/users`（分页/关键词）、`PUT /api/v1/quota/users/{userId}`（套餐 + 每日/总额度覆盖，`0` 表示不限，null 表示继承套餐）、`DELETE .../override` 恢复继承；`GET /api/v1/quota/me` 返回每日/总额度与剩余量快照。
- **手动分配**：`POST /api/v1/quota/users/{userId}/grant` 追加总量并写入 `ai_quota_grant_log` 流水，`GET .../grants` 分页查询，返回分配后的额度快照。
- **运行时拦截**：SSE 建立前预检、`AgentExecutionService.start()` 事务内二次预检、每次模型调用前复检（`QuotaGuardChatModel`，`stream` 用 `Flux.defer` 在订阅时校验）；超额抛 `QuotaExceededException` → 全局处理器返回 HTTP 429 + code `42901` 并显式指定 JSON（避免 `Accept: text/event-stream` 内容协商失败）；流式运行中超额发 `error{code:42901}` 与 `run.completed{status:FAILED}`，保留部分输出。
- **权限与菜单**：Flyway V7 种子 `quota:read` / `quota:write`，ADMIN 拥有读写、USER 不拥有；`/auth/me` 菜单新增「额度管理」（`/admin/quota`）。
- **管理台**：新增「额度管理」页（套餐 / 用户额度 / 分配流水三个视图），侧边栏入口按 `quota:read` 显示；聊天页显示当前用户额度徽标，收到 42901 时展示额度提示。
- **开发账号初始化**：dev 环境创建默认管理员后幂等补齐 `ai_user_quota`（ENTERPRISE 套餐）；超级管理员默认绕过额度限制（`agentforge.quota.bypass-super-admin`）。
- **文档与脚本**：`docs/10-Token额度控制.md`；`scripts/quota-e2e.mjs` 专项验收脚本（套餐/用户/预检/手动分配/流水 19 项）。

### Changed

- `GET /auth/me` 菜单项扩展为 `{code,name,path,icon,permission}`，前端菜单与权限保持一致。
- `mvn verify` 测试规模从 109 增至 138。

### Fixed

- `POST /quota/users/{userId}/grant` 现在返回分配后的用户额度快照（此前返回 `null`，与契约不符）。
- SSE 请求在额度预检失败时不再因内容协商失败退化为 500，而是稳定返回 429 + `42901`。

## [0.1.0] - 2026-10-29

首个开源版本：`git clone` 后零外部依赖启动（H2 + Mock 模型 + 可选 Redis），即可完成「注册/登录 → 建知识库 → 传文档 → 创建 Agent → 流式对话（工具调用/引用）→ Token 审计与 RBAC 管理」闭环。

### Added

- **工程骨架**：Maven 多模块（`common` / `security` / `audit` / `memory` / `rag` / `datasource` / `agent` / `app` + `agentforge-ui`），统一依赖管理；`common` 提供 `ApiResponse`、`BizException`、`ErrorCode`、`PageResult`、`TraceContext`/`TraceIdFilter`、`UserContext`、`TenantContext`、JPA 审计基类、AES-256-GCM 工具。
- **数据库与迁移**：Flyway `V1__core_auth_rbac`、`V2__token_audit_and_model`、`V4__rag`、`V5__datasource`、`V6__agent`（兼容 MySQL 8 与 H2 `MODE=MySQL`），dev profile 使用 H2 文件库，禁用 `ddl-auto=update`。
- **认证与账号**：邮箱验证码发送（60s 冷却、每小时限流、IP 限流、错误次数上限）、注册（默认 `USER` 角色）、账号/邮箱登录、JWT 双 Token（access 2h、refresh 7d，refresh 哈希存储且一次性轮换）、刷新、登出（access `jti` 黑名单 + refresh 吊销）、`GET /auth/me`（用户 + 角色 + 权限 + 菜单）、忘记/重置密码。
- **RBAC**：用户分页/创建/编辑/软禁用/重置密码、角色 CRUD 与权限分配、权限树查询、登录日志查询；内置 `SUPER_ADMIN` / `ADMIN` / `USER` 三个角色与 17 个权限码；接口按权限码 `@PreAuthorize` 鉴权。
- **安全装配**：无状态 `SecurityFilterChain`（认证匿名端点、健康检查、Swagger、dev H2 Console 白名单），JWT 过滤器填充安全上下文，401/403 统一 `ApiResponse` JSON（含 40101/40102 等业务码），`GlobalExceptionHandler` 处理业务异常/参数校验/404/409/500，dev 环境按配置幂等创建默认管理员。
- **模型网关与 Token 审计**：模型配置 CRUD（密钥 AES-GCM 加密存储、测试连接）、`ModelProviderFactory`（DashScope / OpenAI 兼容 / Mock）、零配置 Mock 模型、流式与非流式 usage 采集、单价与成本计算、用量汇总/趋势/分布查询接口。
- **记忆引擎**：分层记忆（工作记忆 / 会话摘要 / 长期记忆），Redis 存储；无 Redis 时自动降级为内存实现，不阻塞启动。
- **RAG 知识库**：知识库/文档/分块管理与文档上传解析（Tika）、切分与向量化、向量检索与引用返回、`knowledge_search` 工具。
- **数据源与沙箱**：数据源注册/连接测试/Schema 采集、只读 SQL 校验（`SqlGuard`：单条 SELECT、禁注释/DML/DDL/加锁、强制 LIMIT、超时）与查询执行、SQL 查询日志；`SandboxProvider` SPI、默认 `ProcessSandboxProvider`（语言白名单、独立临时目录、环境变量白名单、超时杀进程树、输出截断）与 Docker 沙箱扩展点。
- **Agent Harness 与多智能体**：Agent 定义/版本管理、Run/Step 生命周期、SSE 流式对话（增量事件、工具调用事件）、工具中心（`current_time`、`calculator`、`knowledge_search`、`sql_query`、`code_execute`、`remember_fact`、`recall_facts`）与风险等级、ReactAgent 工厂、编排类型 `SINGLE` / `SEQUENTIAL` / `PARALLEL` / `ROUTING` / `SUPERVISOR`、运行上下文与拦截器。
- **Vue 3 控制台**：登录/注册/找回密码、工作台概览、对话（SSE 流式与引用渲染）、知识库、Agent、数据源、沙箱、模型配置、审计看板、用户与角色管理、403/404 页面；路由守卫、权限指令、Token 刷新与 SSE composable。
- **工程质量**：`scripts/smoke-test.mjs` 与 `scripts/smoke-test.ps1` 端到端冒烟脚本（按契约校验、`PASS/SKIP/FAIL` 统计、非 0 退出码）；GitHub Actions `backend-ci`（`mvn -B -ntp verify` + surefire 报告）与前端 `pnpm install --frozen-lockfile && pnpm build`。
- **文档**：README、技术方案、开发路线图、本地开发环境、API 契约、认证与权限设计（`docs/05`）。

### Changed

- 根 `application.yml` 合并各模块配置块（认证、审计、加密、记忆、RAG、沙箱），统一 `agentforge.*` 前缀；JWT/加密密钥支持环境变量覆盖。
- 默认启动改为 dev profile（H2 + Mock 模型），无 Redis、无 Docker 时自动降级。
- 后端启动类排除 Spring Security 默认 `UserDetailsServiceAutoConfiguration`，避免生成内存默认用户，认证完全由自研 JWT 过滤器承担。

### Fixed

- 修复 SSE 流式响应在异步分派阶段被安全链二次拦截导致 403/连接断开的问题：SecurityFilterChain 对 `ASYNC`、`ERROR` 分发类型放行（`dispatcherTypeMatchers(ASYNC, ERROR).permitAll()`）。
- 修复 ReactAgent 构建时 `ChatOptions` 合并导致的 SSE 500；为 `ChatModel` 默认选项增加 Jackson 可序列化包装。
- 修复流式运行中 `MODEL` 步骤回填占位符而非真实输出的问题。
- 修复 flow 类型 `max_iterations` 缺省值处理。

### Security

- JWT 密钥启动校验（≥ 32 字节），access/refresh 双 Token 与 `jti` 黑名单；refresh 仅存哈希并轮换。
- BCrypt（强度 10）存储密码；登录 5 次失败/15 分钟锁定并记录登录日志。
- 邮箱验证码哈希存储、有效期 5 分钟、重发冷却与双维限流、错误次数作废；SMTP 未配置时仅在开发环境日志兜底。
- 模型 API Key 与数据源密码 AES-256-GCM 加密入库；接口不回显明文。
- 只读 SQL 安全校验与查询超时/行数上限；进程沙箱限制语言、环境变量、执行时长与输出体积。
- 审计、登录日志、Token 用量接口只读，统一 `audit:read` 权限码控制。
- 日志与响应携带 `traceId`，不打印密钥、Token、密码与完整 SQL 参数。

[Unreleased]: https://github.com/O-pei/team-agent-forge/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/O-pei/team-agent-forge/releases/tag/v0.1.0
