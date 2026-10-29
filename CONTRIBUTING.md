# 贡献指南（Contributing to AgentForge）

感谢你愿意参与 AgentForge！本文约定本地环境、代码规范、提交与 PR 流程，以及扩展 Agent 工具 / 新模块 / 权限码的具体步骤。

> 动手前请先读：[`docs/01-技术方案.md`](docs/01-技术方案.md)、[`docs/04-API契约.md`](docs/04-API契约.md)（**已冻结，改接口先改契约**）、[`AGENTS.md`](AGENTS.md)。
> 安全相关问题请勿公开提 Issue，见 [`SECURITY.md`](SECURITY.md)。

## 1. 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **21**（Maven `release=21`） | 本机默认 `java` 可能是 1.8，务必显式设置 `JAVA_HOME` |
| Maven | 3.9+ | 直接运行 `mvn` 即可，仓库 `.mvn/maven.config` 已指定项目内本地仓库 |
| Node.js | 20+（CI 使用 22） | 仅前端与冒烟脚本需要，`fetch` 需 Node 18+ |
| pnpm | 10+（CI 使用 10） | 前端包管理器，**不要用 npm/yarn 混装** |
| MySQL 8 / Redis | 可选 | dev 默认 H2 文件库 + 内存记忆降级，无 Docker 也能跑 |

Windows PowerShell 示例（JDK 路径按本机调整）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\latest\jdk-21'
mvn -v   # 应显示 Java version: 21
```

Maven 依赖下载到仓库内 `.m2repo/`（见 `.mvn/maven.config` + `.mvn/settings.xml`，使用 Maven Central 直连）。**不要**改用全局 `settings.xml`，其本地仓库路径在受限环境下不可写。

## 2. 本地启动

```bash
# 后端：dev profile = H2 文件库 + Mock 模型，零外部依赖
mvn -DskipTests package
java -jar agentforge-app/target/agentforge-app-0.1.0-SNAPSHOT.jar
# 或开发期直接：
mvn -pl agentforge-app spring-boot:run
```

- 服务：http://localhost:8080 ，Swagger UI：http://localhost:8080/swagger-ui.html ，健康检查：`/actuator/health`
- dev 默认管理员（仅 dev 由 `DataInitializer` 在不存在时创建）：`admin@agentforge.dev / Admin@123456`，可用环境变量 `AGENTFORGE_ADMIN_PASSWORD` 覆盖；生产必须 `AGENTFORGE_SEED_ADMIN=false`
- 前端：`cd agentforge-ui && pnpm install && pnpm dev`（Vite 代理 `/api` → `http://localhost:8080`）

## 3. 模块边界

依赖方向只允许向下（见 `AGENTS.md`）：

```
app → agent → {memory, rag, datasource} → audit → security → common
```

- `common` 不依赖任何 AgentForge 模块；模块间只能通过 `spi` / `api` 包交互，**禁止跨模块直接使用 Entity / Repository**。
- `agentforge-app` 只做装配（启动类、配置、Flyway、全局异常），不写业务逻辑。

## 4. 分支与提交规范

- 主干 `main` 保持可构建；开发请从最新 `main` 切分支：`feat/xxx`、`fix/xxx`、`docs/xxx`、`chore/xxx`。
- 提交信息格式：`<type>(<module>): <描述>`，type 取值：

| type | 用途 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `docs` | 仅文档 |
| `test` | 仅测试 |
| `chore` | 构建、配置、依赖、脚手架 |

- **一个提交只做一件事**；提交信息用中文或英文均可，但需说明「做了什么、为什么」。
- 只 `git add` 自己改动的文件（推荐 `git add path/to/file` 或 `git commit <paths>`），**禁止 `git add -A`**，避免把 `.data/`、`.m2repo/`、构建产物或他人的中间态提交进来。
- 仓库内**不得出现任何真实密钥、数据库密码、JWT 密钥**。本地敏感配置放 `.env`（已被 `.gitignore` 忽略）或环境变量。
- 遇到并发写同一文件（如根 `pom.xml`、`application.yml`、Flyway 迁移）时，先沟通归属，必要时使用 `index.lock` 重试。

## 5. PR 自查清单

提交 PR 前逐项确认：

- [ ] `mvn -B verify` 通过（CI 等价校验：编译 + 单测 + 集成测试）
- [ ] 前端改动执行 `cd agentforge-ui && pnpm build` 通过（CI 另有 `vue-tsc` 类型检查）
- [ ] 无明文密钥/密码/Token（日志也不要打印 Token、密码、完整 SQL 参数）
- [ ] 未破坏 [`docs/04-API契约.md`](docs/04-API契约.md)：路径、响应结构、错误码、权限码与文档一致
- [ ] 所有对外接口（匿名端点除外）已加 `@PreAuthorize("hasAuthority('xxx:yyy')")`，权限码在迁移中已种子并授权
- [ ] 数据库变更只通过 Flyway 迁移（禁用 `ddl-auto=update`），SQL 同时兼容 MySQL 8 与 H2 `MODE=MySQL`
- [ ] 核心逻辑有单测；安全/沙箱/审计关键路径覆盖率 ≥ 80%（见 `AGENTS.md`）
- [ ] 新增外部依赖已在根 `pom.xml` 的 `dependencyManagement` 声明版本

## 6. 如何新增 Agent 工具

工具由 `AgentToolRegistry` 自动汇总所有 `AgentToolDefinition` Bean，ReactAgent 通过 `methodTools` 注入。新增一个内置工具：

1. 在 `agentforge-agent/src/main/java/com/agentforge/agent/tool/ToolNames.java` 增加工具名常量（模型可见的 `tool name`）。
2. 新建 `@Component` 类实现 `AgentToolDefinition`，提供 `name()`、`description()`、`riskLevel()`（`LOW`/`MEDIUM`/`HIGH`）与 `toolBean()`（返回 `this`）。
3. 在同一个 Bean 上实现实际能力方法，标注 Spring AI 的 `@Tool(name = ToolNames.XXX, description = "...")`，参数用 `@ToolParam(description = "...")`；返回值为字符串或可序列化对象。
4. 在 `/api/v1/tools`（`agent:read`）确认工具已列出，并在单测中覆盖；高风险工具建议要求人工审批（HITL），执行前在 SSE `tool.call` 事件中标注风险等级。
5. 工具运行在 Agent 执行上下文中，鉴权发生在 `/api/v1/chat`、`/api/v1/runs/**` 等入口（`agent:run`），工具内部不要自行放宽校验。

参考实现：`CurrentTimeTool`、`CalculatorTool`、`KnowledgeSearchTool`、`SqlQueryTool`、`CodeExecuteTool`、`RememberFactTool`、`RecallFactsTool`。

## 7. 如何新增模块 / 数据表

1. 新模块放在仓库根目录，`pom.xml` 的 `<parent>` 指向 `agentforge-parent`，并在根 `pom.xml` 的 `<modules>` 中按依赖顺序注册；依赖方向必须符合第 3 节。
2. 跨模块对外能力先落到 `spi` / `api` 包并在契约文档登记，再写实现；`common` 禁止反向依赖。
3. 新表只能通过 `agentforge-app/src/main/resources/db/migration/V{n}__*.sql` 创建，编号分配见 [`docs/04-API契约.md`](docs/04-API契约.md) 第 5 节，**一个迁移文件只能有一个写者**；已应用的迁移不可修改，只能新增版本。
4. 实体继承 `com.agentforge.common.jpa.BaseEntity`（自增 ID、`created_at/updated_at`、`version`），时间字段用 `DATETIME`，布尔用 `BOOLEAN`，避免 MySQL 专有语法。
5. 启动类已配置 `scanBasePackages/EntityScan/EnableJpaRepositories = "com.agentforge"`，新包无需额外注册。

## 8. 如何新增权限码 / 调整角色

认证与 RBAC 的完整设计见 [`docs/05-认证与权限设计.md`](docs/05-认证与权限设计.md)。新增权限码的最小步骤：

1. 在**新**的 Flyway 迁移中 `INSERT INTO af_permission`，并 `INSERT INTO af_role_permission` 授权给内置角色（内置角色默认：`SUPER_ADMIN` 全部、`ADMIN` 管理类、`USER` 只读/运行类）。
2. 在 Controller 方法上加 `@PreAuthorize("hasAuthority('new:code')")`；匿名端点不要加。
3. 若需出现在 `/api/v1/auth/me` 的 `menus` 中，在 `AuthService.buildMenus` 增加映射，并同步前端 `v-permission` 指令与路由守卫。
4. 更新契约文档附录 A 权限码表；不要复用已废弃的权限码。

## 9. 端到端冒烟测试

后端以 dev profile 启动（Flyway V1~V6 已应用、H2 + Mock 模型）后，在仓库根目录执行：

```bash
node scripts/smoke-test.mjs
# 指定地址/账号：
node scripts/smoke-test.mjs --base-url http://127.0.0.1:18080 \
  --account admin@agentforge.dev --password Admin@123456
```

- 默认 `--base-url http://127.0.0.1:18080`、`--api-prefix /api/v1`，可用环境变量 `AF_SMOKE_BASE` 覆盖。
- 脚本按真实 HTTP 与契约校验：`PASS` 通过、`SKIP` 模块未就绪/可选能力未配置、`FAIL` 与契约不符；**退出码 0 表示无 FAIL**。
- 只创建带时间戳前缀的临时数据，不清理也不破坏既有数据；跑完可查看输出的失败明细定位问题。
- Windows 下也可使用 `scripts/smoke-test.ps1`（参数相同，需 PowerShell 5.1+）。

## 10. Issue 约定

仓库当前使用 GitHub Issue 默认表单（尚未提供 `.github/ISSUE_TEMPLATE`），请按以下约定填写：

- 标题前缀：`[bug]`、`[feat]`、`[docs]`、`[question]`。
- Bug 报告必须包含：AgentForge 版本/提交哈希、操作系统与 JDK/Node 版本、启动 profile 与相关配置（**脱敏**）、最小复现步骤、期望与实际结果、关键日志（含 `traceId`）。
- 功能请求请说明使用场景、期望接口/交互与是否有契约影响；涉及 API 变更请先讨论并更新契约文档。
- 安全漏洞**不要**公开提 Issue，按 [`SECURITY.md`](SECURITY.md) 私下上报。

## 11. 文档与契约

- `docs/01~03` 为方案/路线图/环境说明；`docs/04` 为**冻结的 API 契约**（接口、错误码、权限码、迁移编号）；`docs/05` 为认证与权限设计说明。
- 行为与文档不一致时，以代码与迁移为准并**同时**提 PR 修正文档；改契约必须先改 `docs/04` 并经过评审。

## 12. License

贡献即表示同意以 [Apache License 2.0](LICENSE) 授权你的代码。
