# AgentForge 跨模块 API 契约（v0.1.0，冻结）

> 本文件在 P0 冻结，所有模块开发必须遵守；变更需在 PR 描述中注明「契约变更」并由 Lead 更新本文件。
> 对应路线图：[`02-开发路线图.md`](./02-开发路线图.md)

---

## 1. 通用约定

- **Base path**：`/api/v1`；管理端接口前缀相同，权限码控制。
- **响应包装**：除 SSE 外一律返回 `ApiResponse<T>`（`com.agentforge.common.api.ApiResponse`）：
  ```json
  { "code": 0, "message": "OK", "data": {}, "traceId": "…", "timestamp": "2026-10-29T12:00:00Z" }
  ```
- **分页**：`?page=1&size=20`（page 从 1 开始，size ≤ 100），返回 `PageResult<T>`（`records/total/page/size`）。
- **错误码**：`0` 成功；`40000` 参数错误；`40100` 未认证；`40300` 无权限；`40400` 不存在；`40900` 冲突；`42900` 限流；`50000` 内部错误；`50300` 服务不可用。业务细分码以 5 位数字在前缀后扩展，必须登记到 `docs/04-API契约.md` 附录。
- **认证**：`Authorization: Bearer <accessToken>`；access token 2h、refresh token 7d；刷新走 `POST /auth/refresh`（轮换 refresh token）。
- **Trace**：请求可带 `X-Trace-Id`（`[A-Za-z0-9._-]{1,64}`），响应必回显；日志 MDC key 为 `traceId`。
- **时间**：入参/出参 ISO-8601（`yyyy-MM-dd'T'HH:mm:ss`），服务端时区 `Asia/Shanghai`。
- **租户**：所有业务表含 `tenant_id`，默认 `1`；接口不暴露 tenantId 入参（由 Token 推导）。
- **权限**：方法级使用 `@PreAuthorize("hasAuthority('<code>')")`；权限码见附录 A。
- **DTO 命名**：`XxxRequest` / `XxxResponse` / `XxxSummary`；Entity 不出 Controller。

## 2. 接口清单（按模块）

### 2.1 认证与用户（agentforge-security，写者 backend-foundation）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/email-code` | 匿名 | 发送邮箱验证码；body `{email, scene: REGISTER\|LOGIN\|RESET_PASSWORD}`；60s 冷却，同邮箱 1h ≤ 10 次 |
| POST | `/api/v1/auth/register` | 匿名 | `{email, username, password, code}`；密码 ≥ 8 位含字母数字；注册成功默认角色 `USER` |
| POST | `/api/v1/auth/login` | 匿名 | `{account(邮箱或用户名), password}`；返回 `{accessToken, refreshToken, expiresIn, user}`；5 次失败/15min 锁定 |
| POST | `/api/v1/auth/refresh` | 匿名 | `{refreshToken}`；返回新双 Token，旧 refresh 失效 |
| POST | `/api/v1/auth/logout` | 登录 | 吊销当前 refresh token + 加入 access jti 黑名单 |
| GET | `/api/v1/auth/me` | 登录 | 返回 `{user, roles, permissions, menus}` |
| POST | `/api/v1/auth/password/forgot` | 匿名 | `{email}` 发重置码 |
| POST | `/api/v1/auth/password/reset` | 匿名 | `{email, code, newPassword}` |
| POST | `/api/v1/auth/sms-code` | 匿名 | **预留**：`{phone}`，未启用返回 `50300`（`sms-not-configured`） |

用户管理：

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/api/v1/users`（`page,size,keyword,status`） | `user:read` |
| POST | `/api/v1/users` | `user:write` |
| PUT | `/api/v1/users/{id}`（昵称/状态/角色） | `user:write` |
| DELETE | `/api/v1/users/{id}`（软禁用） | `user:write` |
| PUT | `/api/v1/users/{id}/password`（管理员重置） | `user:write` |

角色/权限：

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/api/v1/roles` | `role:read` |
| POST | `/api/v1/roles` | `role:write` |
| PUT | `/api/v1/roles/{id}`（含 permissionIds） | `role:write` |
| DELETE | `/api/v1/roles/{id}`（内置角色不可删） | `role:write` |
| GET | `/api/v1/permissions` | `role:read` |
| GET | `/api/v1/login-logs` | `audit:read` |

### 2.2 模型配置与 Token 审计（agentforge-audit，写者 backend-foundation）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/models` | `model:read` | 模型配置列表（密钥掩码） |
| POST | `/api/v1/models` | `model:write` | `{name, provider(DASHSCOPE\|OPENAI_COMPATIBLE\|MOCK), modelName, baseUrl?, apiKey?, enabled, defaultModel}` |
| PUT | `/api/v1/models/{id}` | `model:write` | 同创建；apiKey 为空表示不变 |
| DELETE | `/api/v1/models/{id}` | `model:write` | |
| POST | `/api/v1/models/{id}/test` | `model:write` | 发送 `ping`，返回延迟与回显 |
| GET | `/api/v1/audit/usage/summary?from&to` | `audit:read` | `{totalPromptTokens,totalCompletionTokens,totalTokens,totalCost,requests}` |
| GET | `/api/v1/audit/usage/trend?from&to&granularity=day\|hour` | `audit:read` | 折线图数据 |
| GET | `/api/v1/audit/usage/breakdown?from&to&groupBy=model\|agent\|user` | `audit:read` | 饼图/排行榜 |
| GET | `/api/v1/audit/usage/records?page&size&from&to&modelId&agentId&userId` | `audit:read` | 明细分页 |
| GET | `/api/v1/audit/usage/export?from&to` | `audit:read` | CSV 下载 |
| GET | `/api/v1/audit/overview` | `audit:read` | 工作台汇总卡片 |

### 2.3 记忆（agentforge-memory，写者 backend-knowledge）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/memory/sessions/{sessionId}/messages?page&size` | `memory:read` | 历史消息 |
| DELETE | `/api/v1/memory/sessions/{sessionId}` | `memory:write` | 清空会话记忆（Redis + checkpointer） |
| GET | `/api/v1/memory/facts?page&size&keyword` | `memory:read` | 长期记忆列表 |
| DELETE | `/api/v1/memory/facts/{id}` | `memory:write` | 删除一条长期记忆 |
| GET | `/api/v1/memory/sessions/{sessionId}/summary` | `memory:read` | 会话摘要（v0.1.0 实现路径；原草案 `/memory/summary/{sessionId}` 未采用，前端按实际路径对接） |

### 2.4 RAG（agentforge-rag，写者 backend-knowledge）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/knowledge-bases?page&size` | `rag:read` | |
| POST | `/api/v1/knowledge-bases` | `rag:write` | `{name, description, embeddingModelId}` |
| PUT | `/api/v1/knowledge-bases/{id}` | `rag:write` | |
| DELETE | `/api/v1/knowledge-bases/{id}` | `rag:write` | 级联删除文档与向量 |
| GET | `/api/v1/knowledge-bases/{id}/documents?page&size&status` | `rag:read` | |
| POST | `/api/v1/knowledge-bases/{id}/documents`（multipart `file`） | `rag:write` | 异步解析；返回 documentId + status |
| GET | `/api/v1/documents/{id}` | `rag:read` | 含处理进度/错误 |
| DELETE | `/api/v1/documents/{id}` | `rag:write` | |
| POST | `/api/v1/documents/{id}/reindex` | `rag:write` | 重建分块与向量 |
| POST | `/api/v1/knowledge-bases/{id}/search` | `rag:read` | `{query, topK, threshold}` → `[{content, score, documentId, chunkIndex, filename}]` |

### 2.5 数据源与沙箱（agentforge-datasource，写者 backend-runtime）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/datasources?page&size` | `datasource:read` | |
| POST | `/api/v1/datasources` | `datasource:write` | `{name, type(MYSQL\|POSTGRESQL\|H2), jdbcUrl, username, password, maxPoolSize}`；密码 AES-GCM 加密存储 |
| PUT | `/api/v1/datasources/{id}` | `datasource:write` | password 空表示不变 |
| DELETE | `/api/v1/datasources/{id}` | `datasource:write` | |
| POST | `/api/v1/datasources/{id}/test` | `datasource:write` | `{success, latencyMs, message}` |
| GET | `/api/v1/datasources/{id}/schema` | `datasource:read` | 表/字段/注释/主键 |
| POST | `/api/v1/datasources/{id}/query` | `datasource:query` | `{sql, maxRows}` → `{columns, rows, elapsedMs, truncated}`；只读 + 强制 LIMIT |
| GET | `/api/v1/datasources/{id}/query-logs?page&size` | `datasource:read` | SQL 审计 |
| POST | `/api/v1/sandbox/execute` | `datasource:query` | `{language: java\|python\|node\|shell, code, timeoutSeconds?, env?}` → `SandboxResult`；默认 Process 沙箱，Docker 可用时可选 |
| GET | `/api/v1/sandbox/providers` | `datasource:read` | 可用沙箱实现列表（process/docker）与能力说明 |

### 2.6 Agent Harness（agentforge-agent，写者 backend-runtime）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/agents?page&size&keyword` | `agent:read` | |
| POST | `/api/v1/agents` | `agent:write` | 创建草稿（v1） |
| GET | `/api/v1/agents/{id}` | `agent:read` | 含当前版本与配置 |
| PUT | `/api/v1/agents/{id}` | `agent:write` | 生成新版本（不可变历史） |
| DELETE | `/api/v1/agents/{id}` | `agent:write` | 软删除 |
| POST | `/api/v1/agents/{id}/publish` | `agent:write` | `{version}` 发布 |
| GET | `/api/v1/agents/{id}/versions` | `agent:read` | 版本列表 |
| POST | `/api/v1/agents/{id}/run` | `agent:run` | 同步运行（非流式，调试用） |
| POST | `/api/v1/chat/stream` | `agent:run` | SSE 流式对话；body `{agentId, sessionId?, message, attachments?}` |
| POST | `/api/v1/runs/{runId}/cancel` | `agent:run` | 取消运行 |
| POST | `/api/v1/runs/{runId}/approve` | `agent:run` | `{approved, comment}` 恢复 HITL 中断 |
| GET | `/api/v1/runs/{runId}` | `agent:read` | 运行详情（状态/步骤/usage） |
| GET | `/api/v1/tools` | `agent:read` | 已注册工具（名称/描述/风险等级/来源） |
| GET | `/api/v1/orchestrations` + CRUD | `agent:read/write` | 多智能体编排定义（SEQUENTIAL/PARALLEL/ROUTING/SUPERVISOR） |

### 2.7 Token 额度（agentforge-audit，写者 audit-memory）

套餐（`ai_quota_tier`）定义每日额度与总额度；用户额度（`ai_user_quota`）可指定套餐、覆盖额度，并由管理员手动分配 token。额度口径统一为 `ai_token_usage.total_tokens` 按用户聚合；额度值 `0` 表示不限；每日按 `agentforge.quota.zone`（默认 `Asia/Shanghai`）自然日重置。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/quota/me` | 登录即可 | 当前用户额度快照 |
| GET | `/api/v1/quota/tiers` | `quota:read` | 套餐列表（含 `builtin` 与使用人数） |
| POST | `/api/v1/quota/tiers` | `quota:write` | `{code, name, description, dailyTokenLimit, totalTokenLimit, sortOrder, enabled}` |
| PUT | `/api/v1/quota/tiers/{id}` | `quota:write` | 修改套餐，`code` 不可改 |
| DELETE | `/api/v1/quota/tiers/{id}` | `quota:write` | 内置套餐或仍被用户引用时返回 409 |
| GET | `/api/v1/quota/users?page&size&keyword` | `quota:read` | 用户额度分页 `{records, total, page, size}` |
| PUT | `/api/v1/quota/users/{userId}` | `quota:write` | `{tierId, dailyLimit, totalLimit}`；字段缺省/null 表示继承套餐，`0` 表示不限 |
| DELETE | `/api/v1/quota/users/{userId}/override` | `quota:write` | 清除覆盖，恢复继承套餐 |
| POST | `/api/v1/quota/users/{userId}/grant` | `quota:write` | `{amount > 0, reason <= 200}`，累加到总额度并记流水 |
| GET | `/api/v1/quota/users/{userId}/grants?page&size` | `quota:read` | 手动分配流水 |

额度快照字段：`userId, tierCode, tierName, dailyLimit, dailyUsed, dailyRemaining, totalLimit, totalUsed, totalRemaining, grantedTokens, unlimited, resetAt`；limit=0 表示不限，unlimited=true 时 remaining=-1。用户额度列表额外返回 `username, email, nickname, status, tierId`。

超额行为：`POST /chat/stream` 与 `/agents/{id}/run` 启动前预检，额度不足返回 HTTP 429 + code `42901`；运行中每次模型调用前复检，超额时停止后续调用，SSE 先发 `error{code:42901,message}` 再发 `run.completed`（status=FAILED，保留已生成内容）。允许最后一次模型调用少量超出，因为真实 token 数在调用完成后才能确定。

## 3. SSE 事件协议（`text/event-stream`）

`event:` 为事件名，`data:` 为单行 JSON；心跳 `event: ping` 每 15s。

| event | data |
| --- | --- |
| `run.started` | `{runId, agentId, sessionId, model}` |
| `message.delta` | `{content}`（增量 token） |
| `message.completed` | `{content}`（完整回复） |
| `tool.call` | `{toolName, arguments, riskLevel}` |
| `tool.result` | `{toolName, resultSummary, success, elapsedMs}` |
| `citation` | `{index, documentId, filename, chunkIndex, score}` |
| `usage` | `{model, promptTokens, completionTokens, totalTokens, cost}` |
| `interrupted` | `{approvalId, toolName, arguments, reason}`（HITL） |
| `run.completed` | `{runId, status: SUCCEEDED\|FAILED\|CANCELLED, elapsedMs, usage}` |
| `error` | `{code, message}` |

前端断开连接时必须调用 `POST /runs/{runId}/cancel`（或由服务端在写失败时取消）。

## 4. Java 模块间契约（SPI）

> 包名规则：`com.agentforge.<module>.spi` 放接口，`com.agentforge.<module>.api` 放跨模块 DTO。**禁止跨模块直接依赖 Entity/Repository**。

### 4.1 security → 所有模块

```java
public final class SecurityUtils {           // com.agentforge.security.util.SecurityUtils
    public static Long currentUserId();       // 未认证返回 null
    public static Long currentUserIdOrThrow();
    public static Set<String> currentRoles();
    public static boolean hasPermission(String code);
}
```

- `TenantContext` / `UserContext` 由 `JwtAuthenticationFilter` 填充并在 `finally` 清理。
- 其它模块通过 Spring Security 表达式鉴权，不直接依赖 security 的内部类。

### 4.1.1 security → audit（额度域读取用户与角色，只读）

```java
package com.agentforge.security.spi;

public interface UserDirectory {
    Page pageUsers(String keyword, int page, int size);
    UserBrief findUser(long userId);
    boolean hasRole(long userId, String roleCode);

    record UserBrief(Long id, String username, String email, String nickname, String status) {}
    record Page(List<UserBrief> records, long total, int page, int size) {}
}
```

- 实现在 security 模块，使用现有用户仓储只读查询，不暴露 Entity。
- audit 的额度服务用该校验目标用户、展示用户名与邮箱，不反向依赖 security 的 Controller/Service。
- security 不依赖 audit；额度写入只发生在 audit。

### 4.2 audit → agent

```java
package com.agentforge.audit.quota;

public interface QuotaService {
    QuotaStatus status(Long userId);
    void assertCanConsume(Long userId);
    List<QuotaTierView> listTiers();
    QuotaTierView createTier(QuotaTierCommand cmd);
    QuotaTierView updateTier(Long id, QuotaTierCommand cmd);
    void deleteTier(Long id);
    PageResult<UserQuotaView> listUsers(String keyword, int page, int size);
    UserQuotaView updateUserQuota(Long userId, UserQuotaCommand cmd);
    void resetUserQuota(Long userId);
    UserQuotaView grantTokens(Long userId, long amount, String reason, Long operatorUserId);
    PageResult<QuotaGrantView> listGrants(Long userId, int page, int size);
}
```

- 视图 record 的字段与 §2.7 快照一致；command record 的可空字段表示继承套餐。
- 额度不足抛 com.agentforge.common.exception.QuotaExceededException，流式场景由调用方在每次模型调用前调用 assertCanConsume。

```java
package com.agentforge.audit.gateway;
public interface ModelProviderFactory {                     // 由 audit 实现
    ChatModel getChatModel(Long modelId);                    // null 表示默认模型
    ChatModel getDefaultChatModel();
    Long getDefaultModelId();
}
public record ChatModelInfo(Long id, String name, String provider, String modelName, boolean isDefault) {}
```

```java
package com.agentforge.audit.usage;
public interface TokenUsageService {                         // 由 audit 实现，agent 调用
    void record(TokenUsageEvent event);
}
public record TokenUsageEvent(
    Long tenantId, Long userId, Long agentId, Long runId, String sessionId,
    Long modelId, String modelName, String provider,
    long promptTokens, long completionTokens, long totalTokens,
    boolean stream, boolean success, long latencyMs, String traceId) {}
```

- 流式场景由调用方（agent 的 ModelInterceptor / Hook）累加后一次性上报。
- `ModelProviderFactory` 必须保证：无 API Key 或 provider=MOCK 时返回 `MockChatModel`（响应含 usage），保证零配置可跑。

### 4.3 memory → agent

```java
package com.agentforge.memory.spi;
public interface MemoryStore {
    void appendMessage(String sessionId, MemoryMessage message);
    List<MemoryMessage> getRecentMessages(String sessionId, int limit);
    void clearSession(String sessionId);
}
public record MemoryMessage(String role, String content, Instant createdAt, Map<String,Object> metadata) {}
public interface LongTermMemory {
    void saveFact(Long tenantId, Long userId, String fact, String category, double confidence);
    List<LongTermFact> recall(Long tenantId, Long userId, String query, int topK);
}
public record LongTermFact(Long id, String fact, String category, double score, Instant createdAt) {}
```

`RedisMemoryStore` 在 `agentforge.memory.redis-enabled=false` 时不得被创建；由 `InMemoryMemoryStore` 兜底（`@ConditionalOnProperty` 或 Provider 选择）。

### 4.4 rag → agent

```java
package com.agentforge.rag.spi;
public interface KnowledgeRetriever {
    List<RetrievedChunk> retrieve(Long knowledgeBaseId, String query, int topK, double threshold);
}
public record RetrievedChunk(String content, double score, Long documentId, String filename, int chunkIndex, Map<String,Object> metadata) {}
```

### 4.5 datasource → agent

```java
package com.agentforge.datasource.spi;
public interface SqlQueryService {                    // 内部已做只读校验/超时/行数限制
    SqlQueryResult query(Long datasourceId, String sql, int maxRows);
}
public interface SandboxProvider {                    // 代码沙箱
    SandboxResult execute(SandboxRequest request);
}
public record SandboxRequest(String language, String code, Duration timeout, Map<String,String> env) {}
public record SandboxResult(boolean success, String stdout, String stderr, int exitCode, long elapsedMs, boolean truncated) {}
```

`SandboxProvider` 默认实现为 `ProcessSandboxProvider`（本地进程 + 临时目录 + 超时 kill + 输出截断）；Docker 实现仅在检测到 Docker 时可用（`@ConditionalOnProperty(agentforge.sandbox.docker-enabled=true)`）。

### 4.6 agent → app

- `agentforge-agent` 暴露 `AgentRuntimeService`、`AgentDefinitionService` 等 Bean，`app` 只做装配与扫描，不写业务逻辑。
- `common` 不得依赖任何其它 AgentForge 模块。

## 5. 数据库迁移编号（禁止并发修改同一文件）

| 版本 | 归属 | 内容 |
| --- | --- | --- |
| `V1__core_auth_rbac.sql` | Lead（已完成） | 用户/角色/权限/关联/邮箱验证码/refresh token/登录日志 |
| `V2__token_audit_and_model.sql` | backend-foundation | `ai_model`、`ai_token_usage`、`ai_model_price`，以及 `model:read/write` 权限种子、默认管理员账号 |
| `V3__memory.sql` | audit-memory | 可选（Redis 为主存储；无 Redis 时内存降级不落库，故 v0.1.0 可缺省，版本号留空） |
| `V4__rag.sql` | backend-knowledge | `kb_knowledge_base`、`kb_document`、`kb_chunk` |
| `V5__datasource.sql` | backend-runtime | `ds_datasource`、`ds_query_log` |
| `V6__agent.sql` | backend-runtime | `agent_definition`、`agent_version`、`agent_run`、`agent_run_step`、`agent_orchestration` |
| `V7__token_quota.sql` | audit-memory | `ai_quota_tier`、`ai_user_quota`、`ai_quota_grant_log`，种子权限 `quota:read/write` 与 FREE/PRO/ENTERPRISE 套餐 |

表命名：`af_`（核心）/`ai_`/`kb_`/`ds_`/`agent_` 前缀；字段 snake_case；每表含 `created_at`/`updated_at`/`version`（除日志表）；外键可选，日志/明细表不建外键。

## 6. 前端契约

- API client：`src/api/*.ts` 按模块拆分，统一走 `request` 封装（baseURL `/api/v1`，自动附带 Bearer、401 刷新、错误 message 走 ElMessage）。
- 类型：v0.1 手写 `src/types/*.ts`，与本文档字段一一对应；后续用 `openapi-typescript` 从 `/v3/api-docs` 生成。
- SSE：`EventSource` 无法带 Authorization 头 → 统一用 `fetch` + `ReadableStream` 解析（`src/utils/sse.ts`）。
- 路由权限：`meta.permission`，路由守卫按 `/auth/me.permissions` 过滤。

## 7. 附录 A：权限码（v0.1.0 全量）

`user:read|write`、`role:read|write`、`audit:read`、`model:read|write`、`agent:read|write|run`、`rag:read|write`、`datasource:read|write|query`、`memory:read|write`、`quota:read|write`。

内置角色：`SUPER_ADMIN`（全部）、`ADMIN`（除 `user:write`/`role:write`/`model:write` 外的管理权限，含 `quota:read/write`）、`USER`（`agent:read/run`、`rag:read`、`datasource:read/query`、`memory:read`，不含额度管理）。

默认账号（仅 dev，由 `agentforge-security` 的 `DataInitializer` 在不存在时创建，**不写入 Flyway 迁移**）：`admin@agentforge.dev` / `Admin@123456`；`model:read/write` 等新增权限码由 `V2` 迁移种子并授权给内置角色。

## 8. 附录 B：业务错误码登记

| code | 含义 |
| --- | --- |
| 40101 | access token 过期 |
| 40102 | refresh token 无效/已吊销 |
| 40103 | 账号被锁定 |
| 40104 | 邮箱未验证 |
| 40201 | 验证码错误或已过期 |
| 40202 | 验证码发送过于频繁 |
| 42901 | 模型配额超限 |
| 50301 | Redis 不可用（记忆降级） |
| 50302 | 沙箱不可用 |
| 50303 | 模型服务不可用 |
