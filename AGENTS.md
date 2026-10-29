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

P0 完成中：工程骨架、Flyway V1、契约冻结。接下来 P1~P7 并行，任务见团队任务板与 `docs/02-开发路线图.md`。
