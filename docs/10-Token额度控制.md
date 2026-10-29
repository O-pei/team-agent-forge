# Token 额度控制（v0.2.0）

> 归属模块：`agentforge-audit`（额度域，SPI 见契约 §4.2 `QuotaService`）；接口契约见 `docs/04-API契约.md` §2.7；迁移文件 `V7__token_quota.sql`（Lead 冻结，勿改）。
> 本文按冻结契约与现有后端实现整理；未实现能力统一放在第 8 节。

## 1. 定位

v0.1.0 只有 Token 用量审计：每次模型调用写 `ai_token_usage`，能看总花费，但没有任何消费上限。v0.2.0 增加 Token 额度控制，解决三件事：

- 按套餐限制每个用户的每日与总额度；
- 允许管理员给单个用户覆盖额度、手动追加 token；
- 额度用尽时在后端统一拦截，前端拿到 429 / `42901` 提示，而不是等账单才发现。

额度只做「拦截」不做「预扣」，真实 token 数在模型调用完成后才能确定（见 5.3）。

## 2. 数据模型

V7 新增三张表，全部 MySQL 8 与 H2（MODE=MySQL）兼容。

### 2.1 ai_quota_tier（套餐）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT PK AUTO_INCREMENT | |
| code | VARCHAR(32) UNIQUE | 套餐编码，创建后不可修改（PUT 忽略 code） |
| name | VARCHAR(64) | 展示名 |
| description | VARCHAR(255) | 说明 |
| daily_token_limit | BIGINT DEFAULT 0 | 每日额度，0 表示不限 |
| total_token_limit | BIGINT DEFAULT 0 | 总额度，0 表示不限 |
| sort_order | INT DEFAULT 0 | 列表排序 |
| enabled | BOOLEAN DEFAULT TRUE | 停用后不再可选 |
| builtin | BOOLEAN DEFAULT FALSE | 内置套餐，不可删除 |
| created_at / updated_at / version | DATETIME / DATETIME / BIGINT | 通用审计字段 |

索引：`uk_ai_quota_tier_code(code)`、`idx_ai_quota_tier_enabled(enabled, sort_order)`。

V7 内置三档（`builtin=TRUE`）：

| code | name | 每日额度 | 总额度 |
| --- | --- | --- | --- |
| FREE | 体验版 | 20000 | 200000 |
| PRO | 专业版 | 200000 | 5000000 |
| ENTERPRISE | 企业版 | 0（不限） | 0（不限） |

### 2.2 ai_user_quota（用户额度）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT PK | |
| tenant_id | BIGINT DEFAULT 1 | |
| user_id | BIGINT UNIQUE | 一个用户一行 |
| tier_id | BIGINT NULL | 指定套餐；NULL 表示未指定 |
| daily_limit_override | BIGINT NULL | 每日额度覆盖；NULL 表示继承套餐，0 表示不限 |
| total_limit_override | BIGINT NULL | 总额度覆盖；NULL 表示继承套餐，0 表示不限 |
| granted_tokens | BIGINT DEFAULT 0 | 累计手动分配量 |
| created_at / updated_at / version | | |

索引：`uk_ai_user_quota_user(user_id)`、`idx_ai_user_quota_tier(tier_id)`。

### 2.3 ai_quota_grant_log（手动分配流水）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT PK | |
| tenant_id | BIGINT DEFAULT 1 | |
| user_id | BIGINT | 被分配用户 |
| operator_id | BIGINT NULL | 操作的管理员 |
| amount | BIGINT | 本次追加 token 数，必须 > 0 |
| reason | VARCHAR(255) | 原因（接口层限制 ≤ 200 字符） |
| balance_after | BIGINT DEFAULT 0 | 分配后的额度余额快照 |
| created_at | DATETIME | 无 updated_at / version（流水表只增不改） |

索引：`idx_ai_quota_grant_user(user_id, created_at)`。

### 2.4 ai_token_usage 索引

V7 额外建了 `idx_ai_token_usage_user_created(user_id, created_at)`，因为每日额度要按「用户 + 当日时间窗」聚合，V2 的单列索引不够。

### 2.5 权限码

V7 写入 `quota:read`（查看额度）与 `quota:write`（管理额度），并授予 `SUPER_ADMIN`、`ADMIN` 两个角色。普通用户不持有这两个权限码，只能通过 `GET /quota/me` 看自己的额度（登录即可，无需权限码）。

## 3. 额度计算口径

统一口径：额度按用户聚合 `ai_token_usage.total_tokens`，不做模型分档、不做 Agent 分档。

### 3.1 有效额度

用户有效额度 = 用户覆盖值，若覆盖为 NULL 则取套餐值：

```
dailyLimit = daily_limit_override ?? tier.daily_token_limit
baseTotal  = total_limit_override ?? tier.total_token_limit
totalLimit = baseTotal == 0 ? 0 : baseTotal + granted_tokens
```

- 0 表示不限（不是「没有额度」）。
- 用户无 `ai_user_quota` 记录、或指定套餐被停用/不存在时，按默认套餐 FREE 计算。
- 手动分配：`grant` 把 amount 累加到 `granted_tokens` 并写 `ai_quota_grant_log`；总额度基准为 0（不限）时不再累加，继续返回 0 表示不限。
- SUPER_ADMIN 默认豁免（`agentforge.quota.bypass-super-admin=true`）：快照强制为 `dailyLimit=0`、`totalLimit=0`、`unlimited=true`。

### 3.2 已用量

```
dailyUsed = SUM(total_tokens) WHERE user_id = ? AND created_at >= 当日 00:00 AND created_at < 次日 00:00
totalUsed = SUM(total_tokens) WHERE user_id = ?
```

自然日按配置时区 `agentforge.quota.zone`（默认 `Asia/Shanghai`，见 9.2 配置项）划分；跨零点后日用量自动归零，`resetAt` 返回次日零点。

### 3.3 快照字段

`GET /quota/me`、`PUT /quota/users/{userId}`、`POST /quota/users/{userId}/grant` 返回的快照字段一致（`QuotaStatus`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| userId | Long | 用户 ID |
| tierCode / tierName | String | 生效套餐编码与名称 |
| dailyLimit | long | 有效每日额度，0=不限 |
| dailyUsed | long | 当日已用 token |
| dailyRemaining | long | 当日剩余；不限时为 -1 |
| totalLimit | long | 有效总额度，0=不限 |
| totalUsed | long | 累计已用 token |
| totalRemaining | long | 总额度剩余；不限时为 -1 |
| grantedTokens | long | 手动分配累计量 |
| unlimited | boolean | 每日与总额度都不限时为 true |
| resetAt | LocalDateTime | 次日零点（时区同 `agentforge.quota.zone`） |

## 4. 接口清单

全部返回 `ApiResponse<T>`；分页 `page` 从 1 开始，`size ≤ 100`。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/quota/me` | 登录即可 | 当前用户额度快照 |
| GET | `/api/v1/quota/tiers` | `quota:read` | 套餐列表，含 `builtin` 与使用人数 |
| POST | `/api/v1/quota/tiers` | `quota:write` | 创建套餐 |
| PUT | `/api/v1/quota/tiers/{id}` | `quota:write` | 修改套餐，`code` 不可改 |
| DELETE | `/api/v1/quota/tiers/{id}` | `quota:write` | 内置套餐或仍被用户引用返回 409 |
| GET | `/api/v1/quota/users?page&size&keyword` | `quota:read` | 用户额度分页 |
| PUT | `/api/v1/quota/users/{userId}` | `quota:write` | 设置套餐与覆盖额度 |
| DELETE | `/api/v1/quota/users/{userId}/override` | `quota:write` | 清除覆盖，恢复继承套餐 |
| POST | `/api/v1/quota/users/{userId}/grant` | `quota:write` | 手动追加 token 并写流水 |
| GET | `/api/v1/quota/users/{userId}/grants?page&size` | `quota:read` | 手动分配流水 |

`GET /quota/users` 的记录字段 = 快照字段 + `username, email, nickname, status, tierId`（用户信息通过 `security.spi.UserDirectory` 只读补齐，audit 不反向依赖 security 的 Service）。

### 4.1 创建/修改套餐

请求体 `{code, name, description, dailyTokenLimit, totalTokenLimit, sortOrder, enabled}`；`PUT` 时 `code` 被忽略。

```
POST /api/v1/quota/tiers
{"code":"TEAM","name":"团队版","description":"每日 100 万","dailyTokenLimit":1000000,"totalTokenLimit":0,"sortOrder":40,"enabled":true}
```

### 4.2 设置用户额度

请求体 `{tierId, dailyLimit, totalLimit}`：字段缺省或 null 表示继承套餐，`0` 表示不限。

```
PUT /api/v1/quota/users/42
{"tierId":4,"dailyLimit":1,"totalLimit":null}
```

### 4.3 手动分配

请求体 `{amount > 0, reason ≤ 200}`，累加到总额度并记流水：

```
POST /api/v1/quota/users/42/grant
{"amount":1000,"reason":"季度预算追加"}
```

### 4.4 查询

```
GET /api/v1/quota/me
GET /api/v1/quota/tiers
GET /api/v1/quota/users?page=1&size=20&keyword=alice
GET /api/v1/quota/users/42/grants?page=1&size=10
```

## 5. 超额行为

### 5.1 预检

`POST /api/v1/chat/stream`、`POST /api/v1/agents/{id}/run` 在启动运行前调用 `QuotaService.assertCanConsume(userId)`；不足时返回 **HTTP 429**，业务码 **`42901`**（`ErrorCode.QUOTA_EXCEEDED`，由 `com.agentforge.common.exception.QuotaExceededException` 抛出），`data` 为空。

### 5.2 运行中复检

运行过程中每次模型调用前再次 `assertCanConsume`：

- 超额时停止后续模型调用，不再发起新请求；
- 流式场景 SSE 先发 `error{code:42901,message}`，再发 `run.completed`（`status=FAILED`）；
- 已经产生的 delta 内容保留，不回收；
- 同步接口返回 429 / `42901`。

### 5.3 允许最后一次少量超出

真实 token 数在调用返回后才写入 `ai_token_usage`，预检只能看到「上一轮结束」的用量。因此最后一次调用可能让已用量略微超过额度（最多一个模型调用的输出长度），这是设计取舍，不是缺陷。

### 5.4 错误响应映射

`agentforge-app` 的 `GlobalExceptionHandler` 已经为本功能做好映射：

- `@ExceptionHandler(QuotaExceededException.class)` 固定返回 HTTP 429 + 业务码 `42901`；
- `statusOf` 同时把 `42900`、`42901`、`40202` 映射为 HTTP 429，其它 4xxxx 仍落到 400。

前端据此判断：HTTP 429 且 `code=42901` 即额度用尽，`code=42900` 为限流（如登录失败次数），两者提示语不同。

## 6. 管理操作流程

1. 建套餐：`POST /quota/tiers`（自定义 code，如 TEAM）；内置 FREE/PRO/ENTERPRISE 由 V7 预置。
2. 给用户分配：`PUT /quota/users/{userId}`，`tierId` 指定套餐，`dailyLimit` / `totalLimit` 按需覆盖（null 继承）。
3. 追加额度：`POST /quota/users/{userId}/grant`，amount 累加、流水可追溯。
4. 查流水：`GET /quota/users/{userId}/grants`。
5. 恢复默认：`DELETE /quota/users/{userId}/override` 清除覆盖，回到继承套餐。
6. 删除套餐：`DELETE /quota/tiers/{id}`，内置套餐或仍被引用返回 409；先清覆盖/换套餐再删。

## 7. 端到端验收脚本

脚本：`scripts/quota-e2e.mjs`（Node 24 原生 fetch，无第三方依赖）。

```
node scripts/quota-e2e.mjs
node scripts/quota-e2e.mjs --base-url http://127.0.0.1:18080 --account admin@agentforge.dev --password 'Admin@123456'
```

参数：`--base-url`（默认 18080）、`--account` / `--password`（默认 dev 管理员）、`--api-prefix`（默认 `/api/v1`）、`--chat-timeout`（默认 180s）。

步骤：

1. `GET /actuator/health`；
2. 管理员登录；
3. `GET /quota/me` 校验快照 12 个字段齐全；
4. `GET /quota/tiers` 校验内置 FREE 存在；
5. 建临时智能体（chat 需要一个 agentId）；
6. 建临时套餐（code 带时间戳）；
7. `POST /users` 建临时用户（不传 roleIds，默认 USER 角色，已含 `agent:run`）；
8. `GET /quota/users?keyword=` 搜到该用户；
9. `PUT /quota/users/{id}` 设 `dailyLimit=1`；
10. 临时用户登录，第一次 `/chat/stream` 放行；
11. 第二次 `/chat/stream` 期望 HTTP 429 + `42901`；
12. 管理员 `grant` 1000，再聊一次仍期望 429（每日额度仍为 1）；
13. `PUT dailyLimit=0`，再聊一次放行；
14. 查 grants 流水包含 amount=1000；
15. `GET /quota/me` 校验 `dailyLimit=0`、`dailyRemaining=-1`（注意：`unlimited` 只在每日与总额度都为 0 时才为 true，本例总额度仍有限）；
16. 清理：清覆盖、软删除临时用户、删临时套餐与智能体（尽力而为，失败不影响结论）。

判定与退出码：每步打印 PASS/SKIP/FAIL；额度接口未上线（404/50300）时整体 SKIP 退出 0；存在 FAIL 退出 1。脚本只创建带时间戳的临时数据，不触碰既有用户与套餐。

## 8. 已知限制

1. 单实例语义：预检是「读-判-用」三步，非原子操作；单实例足够，多实例需 Redis/DB 原子计数（见第 9 节）。
2. 并行少量超出：同一用户并发多个运行，可能同时通过预检、各自消费，导致超出额度；本期不解决。
3. 允许最后一次调用少量超出（见 5.3）。
4. 额度粒度只有「每日 + 总额度」：无月度、季度、按模型或按 Agent 分档。
5. `grant` 只增加总额度，不重置当日用量、不清 `dailyLimit`；每日额度仍按自然日重置。
6. SUPER_ADMIN 默认豁免额度（`agentforge.quota.bypass-super-admin=true`）；ADMIN 不受豁免，需要额度时按普通用户处理。关闭豁免后 SUPER_ADMIN 也按普通规则计算。
7. 额度配置变更对进行中的运行在「下一次模型调用」生效，不会中断已经开始的调用。
8. 时区固定为配置项，改动不会换算历史数据；`resetAt` 只表达下次重置时间。
9. 没有额度告警、邮件通知、额度申请审批流。
10. 额度统计依赖 `ai_token_usage` 的写入时机：一次调用结束才落库，因此「当日已用」在下一次预检才可见（见 5.3）。

## 9. 升级方向与配置

### 9.1 升级方向

- 原子扣减：Redis `INCRBY` + 过期键，或 DB 行锁（`SELECT ... FOR UPDATE`）实现「预留-回写」，消除并发超出。
- 月度/季度额度、按模型或 Agent 分档、预算团队共享池。
- 阈值告警（80%/100%）、邮件与站内信、额度申请与审批。
- 用量预测与异常检测（单日突增告警）、账单导出。

### 9.2 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `agentforge.quota.zone` | `Asia/Shanghai` | 每日额度自然日时区，同时决定 `resetAt` |
| `agentforge.quota.bypass-super-admin` | `true` | SUPER_ADMIN 是否豁免额度；关闭后超管也按普通规则计算 |

### 9.3 代码对应

| 位置 | 说明 |
| --- | --- |
| `agentforge-app/src/main/resources/db/migration/V7__token_quota.sql` | 表、内置套餐、权限码、usage 索引 |
| `com.agentforge.audit.quota.QuotaService` | 额度 SPI：status / assertCanConsume / 套餐 CRUD / 用户额度 / grant / 流水 |
| `com.agentforge.audit.quota.QuotaStatus` | 额度快照 record |
| `com.agentforge.audit.quota.api.QuotaTierView` / `UserQuotaView` / `QuotaGrantView` | 套餐、用户额度、分配流水的视图 record |
| `com.agentforge.audit.quota.api.QuotaTierCommand` / `UserQuotaCommand` / `QuotaGrantCommand` | 管理端入参 record |
| `com.agentforge.common.api.ErrorCode#QUOTA_EXCEEDED` | 业务码 42901 |
| `com.agentforge.common.exception.QuotaExceededException` | 额度不足异常 |
| `com.agentforge.security.spi.UserDirectory` | 只读用户信息，供额度管理列表展示 |
| `scripts/quota-e2e.mjs` | 端到端验收脚本 |
