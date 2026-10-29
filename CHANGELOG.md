# Changelog

本文档格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

暂无（欢迎通过 Issue/PR 贡献）。

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
