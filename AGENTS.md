# AGENTS.md — AgentForge 开发约定（供人与 AI 协作者使用）

> 先读 [`docs/01-技术方案.md`](docs/01-技术方案.md)、[`docs/02-开发路线图.md`](docs/02-开发路线图.md)、[`docs/04-API契约.md`](docs/04-API契约.md)，再动手。

## 1. 环境与构建

- **JDK 21**（Maven 编译 release=21）。本机若默认 `java` 为 1.8，请显式设置：
  ```powershell
  $env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
  ```
- **Maven 3.9+**。仓库根目录已提供 `.mvn/maven.config` + `.mvn/settings.xml`：
  依赖下载到项目内 `.m2repo/`，使用 Maven Central 直连。**直接运行 `mvn` 即可**，不要用全局 settings（其 `D:\MavenRepository` 在受限环境下不可写）。
- 常用命令：
  ```bash
  mvn -DskipTests package            # 全量编译打包
  mvn -pl agentforge-<module> -am test   # 单模块 + 依赖测试
  mvn -B verify                      # CI 等价校验（单测 + 集成测试）
  ```
- 后端启动：`mvn -pl agentforge-app spring-boot:run`（默认 profile `dev`，H2 文件库 `.data/agentforge`，端口 8080）。
- 前端（`agentforge-ui/`）：`pnpm install && pnpm dev`（Vite 代理 `/api` → `http://localhost:8080`）。

## 2. 模块边界（依赖方向只允许向下）

```
app --> agent --> {memory, rag, datasource} --> audit --> security --> common
```

- `common` 不依赖任何 AgentForge 模块；禁止跨模块直接使用 Entity / Repository，只能走 `spi` / `api` 包。
- 模块间接口契约见 `docs/04-API契约.md` 第 4 节，改动必须先改契约文档。
- `agentforge-app` 只做装配（启动类、配置、Flyway、全局异常），不写业务逻辑。

## 3. 数据库

- **只允许 Flyway 迁移建表**，禁止 `ddl-auto=update`（dev 也是 `validate`）。
- 迁移文件在 `agentforge-app/src/main/resources/db/migration/`，编号分配见契约文档第 5 节；**一个文件只能有一个写者**。
- SQL 必须同时兼容 MySQL 8 与 H2（`MODE=MySQL`）：避免 MySQL 专有函数、`JSON` 类型用 `TEXT`、布尔用 `BOOLEAN`。
- 时间字段 `DATETIME`，由 JPA `@PrePersist/@PreUpdate` 维护 `created_at/updated_at`。

## 4. 代码规范

- Java 21；包名 `com.agentforge.<module>.<layer>`；构造器注入（`@RequiredArgsConstructor`），禁止字段 `@Autowired`。
- 对外 DTO 用 `record`；Entity 继承 `com.agentforge.common.jpa.BaseEntity`。
- 异常统一抛 `BizException`，由 `GlobalExceptionHandler` 转 `ApiResponse`；不要吞异常。
- 日志：`log.info("... traceId={}", ...)` 或依赖 MDC；**禁止打印密钥、Token、密码、完整 SQL 参数值**。
- 所有对外接口必须标注 `@PreAuthorize` 权限码（匿名接口除外）。
- 新依赖必须先在根 `pom.xml` 的 `dependencyManagement` 声明版本。

### 4.1 注释规范（强制）

- 注释一律用中文纯文本，不使用 Markdown 语法：不要反引号、加粗、行首列表符号、标题、表格、链接与代码围栏。
- 不要 HTML 排版标签（p、br、ul、ol、li、pre、code 等），不要 ASCII 图、大段清单或示例。
- 类级注释：核心类（SPI、模型网关、审计记账、安全认证、SQL 防护、沙箱、Agent 运行时与编排、全局异常、启动与安全配置）用 2~6 行说明职责与关键约束；普通类（Controller/Service/Entity/Repository/DTO/Util/测试）用 1~2 行写明类名与用途，能删则删。
- 方法注释只保留核心方法、复杂逻辑、边界与安全校验，1~3 行说明做什么、为什么；删除复述方法名的注释；getter/setter 与简单 CRUD 不加注释。@param、@return 只在确有帮助时保留且简洁。
- 代码标识符直接写名字，尽量不用 {@code}；禁止 @author、@version。
- 提交前自检：`node scripts/check-comments.mjs`，必须 0 违规。

## 5. 安全红线

- 仓库内**不得出现任何真实 API Key / 数据库密码 / JWT 密钥**；本地放 `.env`（已被 `.gitignore` 忽略）或环境变量。
- 模型密钥、数据源密码入库前必须 AES-GCM 加密（`CryptoUtils`）。
- SQL 只读校验、沙箱命令白名单、上传文件类型/大小校验是发布门槛，不允许「先跳过」。

## 6. 测试与提交

- 每个模块核心逻辑必须有单测；安全/沙箱/审计关键路径覆盖率 ≥ 80%。
- 提交信息：`feat(module): ...`、`fix(module): ...`、`docs: ...`、`test: ...`、`chore: ...`，一个提交只做一件事。
- PR 前自查：`mvn verify`（后端）、`pnpm build`（前端）、无明文密钥、契约未破坏。
- 已知环境限制：无 Docker / 无本地 Redis 时，相关能力必须自动降级（InMemory / Mock / Process 沙箱），不得阻塞启动。

## 7. 当前阶段

**v0.2.0 已交付**（2026-10-30）：在 v0.1.0（多模块骨架、邮箱注册验证 + JWT + RBAC、模型网关 + Token 审计、Redis/InMemory 记忆、RAG 知识库、数据源只读直连、ProcessSandbox、多智能体 Harness + SSE、Vue3 前端）之上新增 **Token 额度控制**：套餐档位 + 用户覆盖 + 手动追加，每日/总额度双约束，超额 429/42901（SSE 前预检 + 每次模型调用前复检 + 运行中留痕拦截），管理台与 `docs/10`、`scripts/quota-e2e.mjs`。

- 模块设计文档：`docs/05` 认证与权限、`docs/06` 模型网关/审计/记忆、`docs/07` RAG/数据源/沙箱、`docs/08` 前端、`docs/09` 多智能体 Harness、`docs/10` Token 额度控制。
- 已知边界与计划：见各文档「已知限制」章节与 `README.md` 能力矩阵（SUPERVISOR 为 LlmRoutingAgent 近似、沙箱无强隔离、`OPENAI_COMPATIBLE` 未接入、多轮上下文装配等）。
- 下一步（v0.3）：Graph 循环编排、Docker/gVisor 沙箱、pgvector、MCP 管理、真实 DashScope 回归、预算告警完善。
