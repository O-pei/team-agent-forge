# AgentForge UI

AgentForge 控制台前端：Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router + ECharts + axios。

## 技术栈

| 能力 | 选型 |
| --- | --- |
| 框架 | Vue 3.5（`<script setup>` + Composition API）、TypeScript 5.7（`strict`） |
| 构建 | Vite 6、`vue-tsc --noEmit` 类型检查 |
| UI | Element Plus 2（全量引入 + 中文语言包）、深色侧边栏 + 浅色内容区 |
| 状态 | Pinia（`stores/auth.ts`） |
| 路由 | Vue Router 4（history 模式 + 权限守卫） |
| 图表 | ECharts 5（`echarts/core` 按需注册，封装为 `components/Chart.vue`） |
| 请求 | axios（统一解包 `ApiResponse`、401 单飞 refresh、统一错误提示） |
| 流式 | `fetch` + `ReadableStream` 解析 SSE（`utils/sse.ts` + `composables/useSse.ts`） |
| Markdown | markdown-it + highlight.js（`utils/markdown.ts`） |

## 快速开始

```bash
cd agentforge-ui
pnpm install
pnpm dev          # http://127.0.0.1:5173
pnpm build        # vue-tsc --noEmit && vite build
pnpm typecheck    # 仅类型检查
pnpm preview      # 预览 dist
```

Vite 开发代理把 `/api` 转发到 `VITE_PROXY_TARGET`，默认 `http://127.0.0.1:8080`。本机 8080 被占用时可覆盖：

```powershell
$env:VITE_PROXY_TARGET='http://127.0.0.1:18080'; pnpm dev
```

### 环境变量（`.env.development`）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VITE_API_BASE` | `/api/v1` | 接口前缀（契约 base 为 `/api/v1`，不要写成 `/api`） |
| `VITE_PROXY_TARGET` | `http://127.0.0.1:8080` | Vite dev 代理目标 |
| `VITE_ENABLE_MOCK` | `true` | 后台未就绪时是否启用演示数据兜底（生产构建默认不启用） |

### 演示数据（mock 兜底）

后端接口未就绪或返回可用性错误（网络错误 `-1`、`40400`、`5xx`、`50300`）时，仅在 `import.meta.env.DEV` 且 `VITE_ENABLE_MOCK !== 'false'` 下回退到 `src/utils/mock.ts` 的演示数据，并在顶栏显示「演示数据」标记（可点开查看具体来源）。未登录开发者可用演示账号直接进入界面：

```
admin@agentforge.dev / Admin@123456
```

接口就绪后无需改页面，`withMock()` 会自动优先使用真实响应。

## 目录结构

```
src/
├── api/            # 按契约分模块的接口封装（auth/users/roles/models/agents/knowledge/datasources/audit/sandbox）
├── components/     # Chart（ECharts 封装）、StatCard（指标卡）
├── composables/    # useSse（对话流式状态机）
├── directives/     # v-perm 权限指令
├── layouts/        # MainLayout（深色侧边栏）、AuthLayout（登录/注册分栏）
├── router/         # 路由表 + 登录/权限守卫
├── stores/         # auth store（token、用户、角色、权限、mock 模式）
├── styles/         # 全局样式与 CSS 变量
├── types/          # 契约 DTO 类型定义
├── utils/          # request / sse / mock / format / markdown / token
└── views/          # 页面（含 admin/、error/）
```

## 页面与权限

| 路由 | 页面 | 权限码 |
| --- | --- | --- |
| `/login` `/register` `/forgot` | 登录 / 注册（邮箱验证码）/ 忘记密码 | 公开 |
| `/dashboard` | 工作台：指标卡、Token 趋势、Agent 占比、最近调用 | `audit:read` |
| `/chat` | 对话：SSE 流式、Markdown、工具调用折叠、引用、usage、审批 | `agent:run` |
| `/agents` | Agent 管理：CRUD、发布版本、版本历史、跳转对话 | `agent:read` |
| `/knowledge` | 知识库：库 CRUD、文档上传、状态轮询、检索测试 | `rag:read` |
| `/datasources` | 数据源：CRUD、连接测试、表结构、SQL 查询、查询日志 | `datasource:read` |
| `/sandbox` | 代码沙箱：java/python/node/shell 执行、stdout/stderr | `datasource:read`（执行需 `datasource:query`） |
| `/audit` | 审计看板：区间筛选、汇总、趋势、分布、明细、CSV 导出 | `audit:read` |
| `/models` | 模型配置：CRUD、连通性测试、默认模型 | `model:read` |
| `/admin/users` | 用户管理：CRUD、启停用、重置密码、登录日志 | `user:read` |
| `/admin/roles` | 角色权限：CRUD + 权限树勾选 | `role:read` |
| `/403` `/404` | 无权限 / 不存在 | 公开 |

菜单与按钮均由 `auth.hasPermission()` / `v-perm` 按权限码过滤；`SUPER_ADMIN` 全量放行。

## 接口约定

- 所有响应为 `ApiResponse{code,message,data,traceId,timestamp}`，`code === 0` 为成功；`utils/request.ts` 在拦截器中统一解包为 `data`，失败抛 `ApiError`（保留 `code`/`traceId`）。
- 分页统一 `?page=1&size=20`，返回 `PageResult{records,total,page,size}`。
- 认证用 `Authorization: Bearer <accessToken>`；`40100/40101`（或 HTTP 401）触发单飞 refresh 并重试一次，失败后清理会话跳转 `/login?redirect=...`。
- 对话流式仅走 `POST /api/v1/chat/stream`，事件：`run.started`、`message.delta`、`message.completed`、`tool.call`、`tool.result`、`citation`、`usage`、`interrupted`、`run.completed`、`error`；`ping` 忽略。中止时对非 mock 的 runId 调用 `POST /runs/{runId}/cancel`。

## 构建验证说明

本仓库开发机当前运行在受限沙箱内，Node 子进程创建被禁止（`spawn EPERM`），而 Vite 构建需要启动 esbuild 子进程，因此 `pnpm build` 无法在该沙箱内完成；类型检查可正常执行：

```bash
pnpm exec vue-tsc --noEmit   # 0 错误
```

CI（GitHub Actions）中执行 `pnpm install --frozen-lockfile && pnpm build` 作为构建门槛。开发者本机（非沙箱）直接运行 `pnpm build` 即可。

## 已知后续项

- 对话页暂未读取 `?agentId=` 深链参数（Agent 列表页已带参跳转）。
- 编排（`/orchestrations`）、记忆（`/memory/**`）接口已在契约中，但当前控制台暂无独立页面。
- 后端接口未就绪处均以 `// TODO: 等后端 T1/T2...` 或 mock 兜底标注。
