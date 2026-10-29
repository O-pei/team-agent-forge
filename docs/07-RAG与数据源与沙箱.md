# 07 · RAG 知识库 / 数据源直连 / 安全沙箱 设计文档

> 版本：v0.1.0（P0/P1 交付）
> 对应模块：`agentforge-rag`、`agentforge-datasource`、迁移 `V4__rag.sql`、`V5__datasource.sql`、`agentforge-app` 中的 `SandboxController` 装配
> 接口契约：[`docs/04-API契约.md`](04-API契约.md)（已冻结，本文只描述实现，不改变契约）
> 本文所有数值、类名、SQL 字段均逐一核对过源码（commit `ed2476a` / `d1e3437`），不含未实现能力；未实现项统一收敛在文末「已知限制」。

---

## 1. 总体定位与边界

三个能力都通过「SPI 向下、HTTP 向上」的方式接入系统，模块依赖方向遵守
`app --> agent --> {memory, rag, datasource} --> audit --> security --> common`。

```
                    ┌──────────────────────────── agentforge-app（装配/鉴权/Flyway） ───────────────────────────┐
                    │  知识库 Controller        Datasource Controller        Sandbox Controller            │
                    └───────┬──────────────────────┬──────────────────────────┬───────────────────────────┘
                            │                      │                          │
              ┌─────────────▼──────────┐  ┌────────▼─────────────┐  ┌─────────▼──────────────┐
              │ agentforge-rag         │  │ agentforge-datasource│  │ agentforge-datasource  │
              │ DocumentParser(Tika)   │  │ SqlGuard(JSqlParser) │  │ SandboxProvider SPI    │
              │ TextChunker            │  │ Hikari 连接池        │  │ ├ ProcessSandboxProvider│
              │ HashingEmbeddingModel  │  │ CryptoUtils(AES-GCM) │  │ └ DockerSandboxProvider│
              │ SimpleVectorStore      │  │ ds_query_log 审计    │  │   (docker-enabled)     │
              │ ↓ KnowledgeRetriever   │  │ ↓ SqlQueryService    │  │                        │
              └─────────────┬──────────┘  └────────┬─────────────┘  └─────────┬──────────────┘
                            │                      │                          │
               .data/vector-store/*.json     目标业务库(只读)          .data/sandbox/<uuid>/
                            ▲
                    agentforge-agent：KnowledgeSearchTool（工具名 knowledge_search）
```

| 能力 | SPI（已冻结） | 实现类 | 权限码 |
| --- | --- | --- | --- |
| 知识检索 | `com.agentforge.rag.spi.KnowledgeRetriever` | `KnowledgeRetrievalService` | `rag:read` |
| 只读查库 | `com.agentforge.datasource.spi.SqlQueryService` | `DatasourceSqlQueryService` | `datasource:query` |
| 代码沙箱 | `com.agentforge.datasource.spi.SandboxProvider` | `ProcessSandboxProvider` / `DockerSandboxProvider` | `datasource:query` |

所有 HTTP 响应统一为 `ApiResponse<T>`：`{code, message, data, traceId, timestamp}`，成功 `code=0`；分页数据为 `PageResult{records,total,page,size}`。

---

## 2. RAG 知识库

### 2.1 数据模型（`V4__rag.sql`，真实字段）

三张表统一带 `tenant_id BIGINT NOT NULL DEFAULT 1`、`created_at/updated_at DATETIME`、`version BIGINT`（JPA `BaseEntity`）。

**`kb_knowledge_base`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO_INCREMENT | 知识库 ID |
| `name` | VARCHAR(128) NOT NULL | 名称（无唯一约束，允许同名） |
| `description` | VARCHAR(512) NULL | 描述 |
| `embedding_model_id` | BIGINT NULL | 预留：关联 `af_model`，当前写入请求值，尚未参与向量化 |
| `embedding_dimension` | INT NOT NULL DEFAULT 1024 | 与 `HashingEmbeddingModel.DIMENSIONS` 一致 |
| `document_count` / `chunk_count` | INT DEFAULT 0 | 由 `refreshCounts` 每次上传/删除/重建后重算 |
| 索引 | `idx_kb_knowledge_base_tenant(tenant_id)`、`idx_kb_knowledge_base_name(name)` | |

**`kb_document`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `kb_id` | BIGINT NOT NULL | 所属知识库 |
| `filename` | VARCHAR(255) NOT NULL | 原始文件名（去路径、防穿越后的 basename） |
| `content_type` | VARCHAR(128) NULL | 上传时的 Content-Type |
| `file_size` | BIGINT DEFAULT 0 | 字节数 |
| `status` | VARCHAR(16) NOT NULL DEFAULT 'PENDING' | `DocumentStatus` 枚举 |
| `chunk_count` | INT DEFAULT 0 | 切分块数 |
| `error_message` | VARCHAR(1000) NULL | 失败原因（入库前截断 1000 字符） |
| `storage_path` | VARCHAR(512) NULL | 落盘绝对/相对路径（重建索引依赖它） |
| 索引 | `idx_kb_document_kb(kb_id,status)`、`idx_kb_document_created(created_at)` | 前者支撑按状态轮询 |

**`kb_chunk`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `document_id` / `kb_id` | BIGINT NOT NULL | 冗余 `kb_id` 便于按库批量删 |
| `chunk_index` | INT NOT NULL | 文档内从 0 递增 |
| `content` | TEXT NOT NULL | 块正文 |
| `char_count` | INT DEFAULT 0 | 字符数 |
| 索引 | `idx_kb_chunk_document(document_id,chunk_index)`、`idx_kb_chunk_kb(kb_id)` | |

> 实体侧 `content` 使用 `@Column(columnDefinition = "TEXT")` 而非 `@Lob`：Hibernate 6 在 H2(MySQL 模式) 下 `@Lob` 期望 CLOB，与迁移的 TEXT 不符，`ddl-auto=validate` 会启动失败。

### 2.2 索引管线（上传 → READY）

```
POST /api/v1/knowledge-bases/{id}/documents (multipart file)
  └─ DocumentService.upload()            ← 不加 @Transactional，保证异步任务能看到已提交记录
       1. require(kbId)                 不存在 → 40400
       2. file.getSize()>0              否则 400「上传文件不能为空」
       3. DocumentParser.validate()     扩展名白名单 + 20MB 上限 + basename 清洗
       4. file.getBytes() 后再校验一次   防止 multipart 声明大小与实际字节不一致
       5. 落盘 .data/rag-uploads/<kbId>/<uuid><ext>
       6. INSERT kb_document(status=PENDING)
       7. refreshCounts(kbId)
       8. DocumentProcessingService.processAsync(id)   ← @Async，立即返回
  └─ DocumentIngestionService.ingest()   ← @Transactional
       PENDING→PROCESSING → 读文件 → Tika 解析 → TextChunker.split(512,64)
       → deleteByDocumentId → saveAll(kb_chunk) → READY + chunkCount
       → VectorStoreManager.addChunks()    → .data/vector-store/<kbId>.json
       → refreshCounts(kbId)
  失败：DocumentIngestionService.markFailed()（独立事务）→ 清分块、FAILED、error_message 截断 1000
```

`@Async` 与 `@Transactional` 拆成两个 Bean（`DocumentProcessingService` 只负责调度、`DocumentIngestionService` 负责事务），避免 Spring 自调用导致注解失效。

### 2.3 解析：`DocumentParser`（Tika 3.2.3）

| 项 | 值 |
| --- | --- |
| 允许扩展名 | `txt, md, markdown, pdf, docx, html, htm`（小写比较） |
| 单文件上限 | 20MB（`MAX_FILE_SIZE = 20 * 1024 * 1024`） |
| 纯文本处理 | 直接 `new String(bytes, UTF_8)` + 归一化（CRLF→LF、合并连续空格/制表符、3+ 换行压成 2、trim） |
| 二进制/富文本 | `new Tika().parseToString(InputStream, Metadata)`，`Metadata` 带 `RESOURCE_NAME_KEY`/`CONTENT_TYPE` |
| 失败策略 | 解析异常 → 400「文档解析失败: <异常类名>」；解析结果为空 → 400「文档未解析出文本内容」 |

文件名安全：`originalFilename` 先反斜杠转正斜杠取 basename，拒绝空/`.`/`..`，防止 `../` 路径穿越写出上传目录。

### 2.4 切分：`TextChunker`（512 / 64 的出处）

参数就是代码常量 `TextChunker.DEFAULT_CHUNK_SIZE = 512`、`DEFAULT_OVERLAP = 64`（`split` 内部校验 `chunkSize > overlap >= 0`，非法抛 `IllegalArgumentException`）。**不是** `application.yml` 里的 `agentforge.rag.chunk-size=800/chunk-overlap=120`——后者尚未绑定到代码（见「已知限制 L2」）。

算法要点：

1. 归一化后按空行（正则 `\n\s*\n`）切段落；
2. 短段落聚合，累计长度超过 512 前 flush；
3. 单段超过 512 时用 `hardSplit` 按 `start += chunkSize` 硬切，块尾保留 64 字符重叠（尽量从最近的空白处开始，避免把词/字切断）；
4. flush 时把上一块末尾 64 字符作为下一块开头（`tail(previous, overlap)`）；
5. 结果过滤空白块，块内 `strip()`。

例：1500 字符的单段文本 → 起点 0/448/896/1344 → **4 块**（最后一块 156 字符），这是单测 `TextChunkerTest.singleHugeParagraphIsHardSplit` 的断言依据。

### 2.5 嵌入：`HashingEmbeddingModel`（自研、离线、确定性）

```java
@Component
@Primary                                  // 压制 Spring AI Alibaba 自动配置的 dashscopeEmbeddingModel
public class HashingEmbeddingModel implements EmbeddingModel {
    public static final int DIMENSIONS = 1024;
    // 分词：[\p{L}\p{N}]+
    // 含 CJK 的 run：整段(run:) 权重 0.75 + 每码点单字(c:) 1.0 + 相邻 bigram(b:) 0.5
    // 其他 run：w:<词> 权重 1.0
    // FNV-1a(hash=0x811c9dc5, prime=0x01000193) → index=(hash & 0x7fffffff) % 1024
    //                                            sign = ((hash>>>16)&1)==0 ? +1 : -1
    // L2 归一化；空文本 → vector[0]=1
}
```

| 特性 | 说明 |
| --- | --- |
| 维度 | 1024（与 `kb_knowledge_base.embedding_dimension`、`KnowledgeBaseResponse.embeddingDimension` 一致） |
| 确定性 | 同一文本永远得到同一向量（无随机种子、无时间因素），可写单测、可复现 |
| 离线 | 不依赖任何外部 API Key，CI / 内网 / 断网均可跑通全链路 |
| 中英混合 | CJK 走「整段 + 单字 + bigram」三路哈希，中文短查询也能命中 |
| 输出 | 通过 `call(EmbeddingRequest)` 返回 `EmbeddingResponse(List<Embedding>)`，`SimpleVectorStore` 只依赖 `EmbeddingModel` 接口 |

**为什么不用在线 embedding**：v0.1.0 的验收标准是「无外部 Key 也能演示完整 RAG 链路」；在线模型带来 Key 管理、限流、费用、可重复性（同一文档两次入库向量可能漂移）与离线环境不可用等成本。哈希嵌入牺牲语义泛化能力，换取确定性、零依赖、可测试。`EmbeddingModel` 接口与 `embedding_dimension` 字段已预留，替换为在线模型只需新增一个实现并改 `@Primary`/`@Qualifier`（见 5.2）。

### 2.6 向量存储：`VectorStoreManager` + `SimpleVectorStore`

| 项 | 实现 |
| --- | --- |
| 存储后端 | Spring AI `SimpleVectorStore`（内存 + JSON 持久化），构建：`SimpleVectorStore.builder(embeddingModel).build()` |
| 分库 | 每个知识库一个 store，文件 `.data/vector-store/<kbId>.json`（`agentforge.rag.vector-store-dir` 可覆盖） |
| 启动加载 | `@PostConstruct loadExisting()` 扫描目录内 `<数字>.json` 逐个 `load(file)`；单文件损坏只 warn，不影响启动 |
| 懒加载 | 未加载的库在首次访问时 `computeIfAbsent` 构建并读取 |
| 写入 | `addChunks` 用 `new Document(chunkId.toString(), content, metadata{kbId,documentId,chunkIndex,filename})` |
| 持久化 | 先写 `<kbId>.json.tmp` 再 `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`；原子移动失败回退普通 move |
| 删除 | `store.delete(new FilterExpressionBuilder().eq("documentId", id).build())` + 持久化 |
| 并发 | 所有读写 `synchronized (lock)`；单机进程内安全 |
| 删除整库 | `remove(kbId)`：移除内存对象 + 删除 json 文件 |

### 2.7 检索：`KnowledgeRetrievalService`

```java
public interface KnowledgeRetriever {   // 冻结 SPI，rag/spi
    List<RetrievedChunk> retrieve(Long knowledgeBaseId, String query, int topK, double threshold);
    List<RetrievedChunk> retrieveAll(List<Long> knowledgeBaseIds, String query, int topK, double threshold);
}
```

| 参数 | 行为 |
| --- | --- |
| `topK` | `<=0` → 5；否则 `min(topK, MAX_TOP_K=20)` |
| `threshold` | `NaN` → 0.3；否则 clamp 到 `[0,1]` |
| `query` 空 | 直接返回空列表（不查库） |
| 调用底层 | `vectorStoreManager.search(kbId, query, k, minScore)` → `SearchRequest.builder().query().topK().similarityThreshold().build()` |
| 返回 | `RetrievedChunk(content, score, documentId, filename, chunkIndex, metadata)`；`documentId/chunkIndex` 做 Number/String 兼容转换 |
| `retrieveAll` | 逐库检索 → 合并 → 按 score 降序 → 截断 `topK` |

HTTP 层 `KnowledgeRetrievalService.search()` 先 `require(kbId)`（不存在 40400），再映射为契约的 `SearchResultItem(content,score,documentId,chunkIndex,filename)`。

### 2.8 与 Agent `knowledge_search` 工具的关系

`agentforge-agent` 的 `KnowledgeSearchTool`（工具名 `knowledge_search`，`riskLevel=LOW`）**只依赖冻结 SPI** `KnowledgeRetriever`，通过 `ObjectProvider<KnowledgeRetriever>` 软依赖：RAG 模块缺失时返回「知识检索服务尚未配置…」，不会导致 Agent 启动失败。

| 工具参数 | 行为 |
| --- | --- |
| `knowledgeBaseId`（可选） | 非空 → `retrieve(kbId, ...)`；为空 → `retrieveAll(List.of(), ...)`（**当前实现下空列表直接返回空**，即「不指定库」暂不可用，见 L3） |
| `query` | 空 → 返回「检索失败: query 不能为空」 |
| `topK` | 默认 5，上限 20 |
| `threshold` | 工具侧固定 `0.5`（比 HTTP 默认 0.3 更严格） |
| 输出 | JSON `{query, results:[{content,score,documentId,filename,chunkIndex}]}`；同时向 `RunContext` 发 `AgentEvent.CITATION`（引用角标数据来源） |

### 2.9 API 端点（`/api/v1`，除标注外均需登录 + 权限码）

| 方法/路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /knowledge-bases?page=1&size=20` | `rag:read` | 分页列表（id 倒序，size 上限 100） |
| `POST /knowledge-bases` | `rag:write` | body `{name, description, embeddingModelId?}` |
| `GET /knowledge-bases/{id}` | `rag:read` | 详情 |
| `PUT /knowledge-bases/{id}` | `rag:write` | 更新名称/描述 |
| `DELETE /knowledge-bases/{id}` | `rag:write` | 删分块 → 删文档 → 删向量 → 删库 |
| `POST /knowledge-bases/{id}/search` | `rag:read` | body `{query, topK?, threshold?}` |
| `GET /knowledge-bases/{id}/documents?page&size&status` | `rag:read` | 文档列表，`status` 非法 → 400 |
| `POST /knowledge-bases/{id}/documents` | `rag:write` | multipart，字段名 **`file`** |
| `GET /documents/{id}` | `rag:read` | 文档详情（含 status/errorMessage/chunkCount） |
| `DELETE /documents/{id}` | `rag:write` | 删除文档及其分块、向量 |
| `POST /documents/{id}/reindex` | `rag:write` | 重建索引（原始文件不存在 → 400） |

示例（建库 → 上传 → 轮询 → 检索）：

```http
POST /api/v1/knowledge-bases
Authorization: Bearer <token>
Content-Type: application/json

{"name":"产品手册","description":"v0.1 验收用"}

HTTP/1.1 200
{"code":0,"message":"ok","data":{"id":1,"name":"产品手册","embeddingDimension":1024,
 "documentCount":0,"chunkCount":0,"createdAt":"2026-10-29T20:41:00"},"traceId":"..."}
```

```http
POST /api/v1/knowledge-bases/1/documents
Authorization: Bearer <token>
Content-Type: multipart/form-data; boundary=----afBoundary

------afBoundary
Content-Disposition: form-data; name="file"; filename="handbook.txt"
Content-Type: text/plain

AgentForge 支持知识库检索、数据源直连与代码沙箱。
------afBoundary--

{"code":0,"data":{"id":1,"knowledgeBaseId":1,"filename":"handbook.txt","status":"PENDING","chunkCount":0}}
```

```http
POST /api/v1/knowledge-bases/1/search
{"query":"知识库检索","topK":5,"threshold":0.2}

{"code":0,"data":[{"content":"AgentForge 支持知识库检索、数据源直连与代码沙箱。",
  "score":0.2085,"documentId":1,"chunkIndex":0,"filename":"handbook.txt"}]}
```

（实测：`threshold=0.99` 时 `hits=0`，验证阈值生效。）

### 2.10 文档状态机与重试

```
            upload                 ingest 开始             解析+切分+向量化成功
  (无) ───────────▶ PENDING ─────────────────▶ PROCESSING ─────────────────▶ READY
                      │                            │
                      │                            └── 异常 ──▶ FAILED（error_message，清分块）
                      └── reindex ──▶ PENDING（清旧分块/旧向量）→ 再走同一管线
```

| 状态 | 含义 | 触发者 |
| --- | --- | --- |
| `PENDING` | 已落库、等待异步处理 | `upload` / `reindex` |
| `PROCESSING` | 正在解析（处理中的进度可见，便于排查卡死） | `DocumentIngestionService.ingest` |
| `READY` | 分块已入库、向量已持久化 | 同上 |
| `FAILED` | 解析/切分失败或被中断；`error_message` 有原因，`chunk_count=0` | `markFailed` |

重试入口：`POST /api/v1/documents/{id}/reindex`（要求 `storage_path` 文件仍存在；内部清空 `kb_chunk` 与向量库中该文档的记录后重新排队）。失败常见原因：文件被手工删除、类型不在白名单、PDF 为扫描件（无文本层）。

---

## 3. 数据源直连（只读查询）

### 3.1 表结构（`V5__datasource.sql`，真实字段）

**`ds_datasource`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO_INCREMENT | |
| `tenant_id` | BIGINT NOT NULL DEFAULT 1 | |
| `name` | VARCHAR(128) NOT NULL | 显示名（无唯一约束） |
| `type` | VARCHAR(16) NOT NULL | `MYSQL` / `POSTGRESQL` / `H2`（`DataSourceType` 枚举） |
| `jdbc_url` | VARCHAR(1024) NOT NULL | 完整 JDBC URL（跨库需带库名） |
| `username` | VARCHAR(128) NOT NULL | |
| `password_encrypted` | VARCHAR(1024) NOT NULL | `CryptoUtils.encrypt` 后的密文，永不出现在响应里 |
| `read_only` | BOOLEAN NOT NULL DEFAULT TRUE | false 时拒绝一切 query（403） |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE | false 时拒绝 query（403） |
| `max_pool_size` | INT NOT NULL DEFAULT 5 | Hikari 池上限，请求校验 1~20，代码再 `min(20)` |
| 其余 | `created_at/updated_at DATETIME`、`version BIGINT` | |
| 索引 | `idx_ds_datasource_tenant`、`idx_ds_datasource_name` | |

**`ds_query_log`**（审计表，**无** `updated_at`/`version`，不建外键）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `datasource_id` | BIGINT NOT NULL | |
| `user_id` | BIGINT NULL | 当前登录用户（匿名场景为 NULL） |
| `sql_text` | TEXT NOT NULL | 实际执行的 SQL（自动追加 LIMIT 之后），入库前截断 4000 字符 |
| `success` | BOOLEAN NOT NULL | |
| `row_count` | INT NOT NULL DEFAULT 0 | |
| `elapsed_ms` | BIGINT NOT NULL DEFAULT 0 | |
| `error_message` | VARCHAR(1000) NULL | 失败原因（清洗、截断 1000） |
| `created_at` | DATETIME NOT NULL | |
| 索引 | `idx_ds_query_log_ds(datasource_id, created_at)` | 按数据源+时间倒序查日志 |

### 3.2 密码加密与响应约定

- 加密算法：**AES-256-GCM**，公共工具 `com.agentforge.common.crypto.CryptoUtils`（common 模块，无 Spring 依赖）。
- 数据库里存 `v1:` + Base64(12 字节 IV ‖ 密文)，解密失败抛 `IllegalStateException("数据源密码解密失败（密钥可能已更换）")`。
- 密钥优先级：`CryptoUtils.configure()` → 系统属性 `agentforge.crypto.key` → 环境变量 `AGENTFORGE_CRYPTO_KEY` → 本地 dev 默认值（`application.yml` 的 `${AGENTFORGE_CRYPTO_KEY:agentforge-dev-only-crypto-key-change-me}`）。**生产必须用环境变量覆盖**，且密钥丢失后旧密文不可解。
- 响应 DTO `DataSourceResponse` 只暴露 `hasPassword: boolean`，绝不回显明文或密文：

```json
{"id":1,"name":"演示库","type":"MYSQL",
 "jdbcUrl":"jdbc:mysql://127.0.0.1:3306/agentforge_demo?useSSL=false&serverTimezone=Asia/Shanghai",
 "username":"root","hasPassword":true,"readOnly":true,"enabled":true,"maxPoolSize":5,
 "createdAt":"2026-10-29T20:41:00","updatedAt":"2026-10-29T20:41:00"}
```

- 更新时 `password` 留空 = 保留原密码（不会把空密码写进库）；传新密码 = 重新加密并 `evict` 旧连接池。

### 3.3 连接测试与连接池

`POST /api/v1/datasources/{id}/test`（`DataSourceService.test`）：

1. `Class.forName(type.driverClassName())`，驱动缺失 → 返回 `success=false, message="驱动未安装: ..."`；
2. `CryptoUtils.decrypt(password_encrypted)` 失败 → 「密码解密失败」；
3. `DriverManager.setLoginTimeout(5)` + `SELECT 1`（statement timeout 5s）；
4. 返回 `{success, latencyMs, message}`，成功 `message="连接成功"`（实测 `latencyMs=346`）。

查询链路走独立连接池 `DataSourceConnectionManager`（每个数据源一个 Hikari，懒创建）：

| 参数 | 值 |
| --- | --- |
| poolName | `af-ds-<id>` |
| driverClassName | 由 `DataSourceType` 决定 |
| maximumPoolSize | `min(maxPoolSize, 20)`，默认 5 |
| minimumIdle | 0 |
| connectionTimeout / validationTimeout | 5s / 3s |
| idleTimeout / maxLifetime | 60s / 600s |
| readOnly | **true**（Hikari `setReadOnly(true)`） |
| initializationFailTimeout | `-1`（延迟到首次取连接才真正连库，目标库暂时不可用不会让接口 500） |
| 释放 | `update`/`delete` 时 `evict(id)` 关闭池；`@PreDestroy closeAll()` |

### 3.4 只读查询安全链路（`SqlGuard` + `DatasourceSqlQueryService`）

校验顺序（`SqlGuard.validate`，任一失败抛 `BizException(40000)`）：

| # | 检查 | 失败信息（真实文案） |
| --- | --- | --- |
| 1 | SQL 非空（`isBlank` 也算空） | `SQL 不能为空` |
| 2 | `strip()` 后长度 ≤ 10000 | `SQL 过长，最大 10000 字符` |
| 3 | 不允许注释：文本中出现 `--`、`/*`、`*/`、`#` 任一即拒 | `SQL 不允许包含注释` |
| 4 | 禁止加锁读：大小写不敏感匹配 `for update`、`lock in share mode`、`nowait` | `禁止执行加锁查询（FOR UPDATE / LOCK IN SHARE MODE）` |
| 5 | 危险关键字：`insert,update,delete,drop,alter,create,truncate,grant,revoke,merge,call,execute,exec,rename,shutdown,handler,prepare,deallocate,load_file,outfile,dumpfile` | `只允许只读 SELECT 查询，检测到不允许的关键字: DELETE` |
| 6 | `CCJSqlParserUtil.parseStatements` 可解析 | `SQL 解析失败: <首行异常>` |
| 7 | 语句数必须恰好 1 条 | `只允许单条 SQL 语句（检测到 2 条）` |
| 8 | 语句必须是 `Select` | `只允许 SELECT 查询` |
| 9 | 必须是 `PlainSelect`（拒绝 UNION 等集合运算） | `只允许单条简单 SELECT（不支持 UNION / 集合运算）` |
| 10 | 禁止 `SELECT ... INTO`（`getIntoTables`/`getIntoTempTable`） | `禁止 SELECT ... INTO 写出数据` / `禁止 SELECT ... INTO 临时表` |
| 11 | 归一/收紧 `LIMIT`：`normalizeMaxRows`（`<=0→100`，`>500→500`）；无 LIMIT 追加，有 LIMIT 超过上限则收紧；去掉行尾分号 | — |

执行层（`DatasourceSqlQueryService.query`）：

```java
// 1) 数据源校验
if (!TRUE.equals(entity.getEnabled()))   throw BizException.forbidden("数据源已禁用");
if (!TRUE.equals(entity.getReadOnly()))  throw BizException.forbidden("数据源未开启只读模式，禁止执行查询");
// 2) 静态校验 + 自动 LIMIT
GuardedSql guarded = SqlGuard.validate(sql, maxRows);
// 3) 执行
try (Connection conn = connectionManager.getConnection(entity);
     PreparedStatement ps = conn.prepareStatement(guarded.sql())) {
    ps.setQueryTimeout(SqlGuard.QUERY_TIMEOUT_SECONDS);   // 10s
    ps.setMaxRows(guarded.maxRows() + 1);                 // 多取 1 行判断 truncated
    ...
}
// 4) 结果值转换：byte[]→[binary N bytes]，Blob/Clob→摘要，其它 toString 超 4000 字符截断
// 5) 无论成败都 dataSourceService.recordQueryLog(...)，失败信息 sanitize + 截断
```

| 防护项 | 值 |
| --- | --- |
| 语句数 | 1 条（多语句直接拒） |
| 语句类型 | 仅 SELECT（DDL/DML/存储过程/LOAD DATA 均拒） |
| 结果行数 | `maxRows` 默认 100，上限 500；JDBC `setMaxRows(n+1)` + 手工截断，`truncated=true` |
| 查询超时 | `Statement.setQueryTimeout(10)` 秒，超时由驱动中断并返回 400 |
| SQL 长度 | 10000 字符 |
| 审计 | 每次执行写 `ds_query_log`（成功/失败、行数、耗时、错误），失败不影响主流程（只 warn） |

### 3.5 API 端点

| 方法/路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /datasources?page&size` | `datasource:read` | 分页列表（id 倒序，`hasPassword`） |
| `POST /datasources` | `datasource:write` | 创建；类型/URL 前缀不匹配 → 400 |
| `GET/PUT/DELETE /datasources/{id}` | read/write/write | 详情 / 更新（密码留空保留）/ 删除（关池） |
| `POST /datasources/{id}/test` | `datasource:write` | 连接测试（5s 登录超时） |
| `GET /datasources/{id}/schema` | `datasource:read` | 表/列/主键/注释，最多 100 张表 |
| `POST /datasources/{id}/query` | **`datasource:query`** | body `{sql, maxRows?}` |
| `GET /datasources/{id}/query-logs?page&size` | `datasource:read` | 审计日志分页 |

```http
POST /api/v1/datasources/1/query
Authorization: Bearer <token>
Content-Type: application/json

{"sql":"SELECT id, username FROM demo_user","maxRows":10}

{"code":0,"data":{"columns":["id","username"],"rows":[["1","alice"],["2","bob"]],
 "elapsedMs":17,"truncated":false}}
```

```http
POST /api/v1/datasources/1/query   {"sql":"DELETE FROM demo_user"}
HTTP/1.1 400
{"code":40000,"message":"只允许只读 SELECT 查询，检测到不允许的关键字: DELETE"}
```

（实测还覆盖：`SELECT 1; DROP TABLE demo_user` → 400「检测到不允许的关键字: DROP」；`query-logs` 中 `sql` 已自动追加 `LIMIT 10`。）

### 3.6 常见失败排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `驱动未安装: org.postgresql.Driver` | 仓库只打包了 MySQL/H2 驱动（`agentforge-app/pom.xml` 仅 `mysql-connector-j`） | 在 app 模块加 `org.postgresql:postgresql` 依赖（`runtime`） |
| `JDBC URL 与数据源类型不匹配` | `jdbc:mysql:` / `jdbc:postgresql:` / `jdbc:h2:` 前缀与 `type` 不一致 | 改类型或改 URL |
| `Access denied for user` | 账号密码或来源主机限制 | 检查 MySQL 用户授权（`GRANT SELECT ON db.* TO ...`） |
| `Unknown database` | URL 未带库名或库未创建 | URL 补 `/<db>`，或用 mysql CLI 建库 |
| `The server time zone value '?D1ú±ê×?ê±??' is unrecognized`（MySQL 8） | 服务器时区未识别 | URL 加 `serverTimezone=Asia/Shanghai` |
| `Public Key Retrieval is not allowed`（MySQL 8 + caching_sha2） | 连接串未允许公钥检索 | URL 加 `allowPublicKeyRetrieval=true`（内网可信时） |
| `数据源密码解密失败（密钥可能已更换）` | `AGENTFORGE_CRYPTO_KEY` 变了 | 用当前密钥重新保存密码 |
| 403 `数据源未开启只读模式` | 创建时 `readOnly=false` | 更新为 true（设计上非只读数据源一律不允许查询） |
| `SQL 执行失败: ...` | 目标库真实错误（表不存在、超时等） | 看 `ds_query_log.error_message` 与返回体 |

---

## 4. 代码沙箱

### 4.1 SPI（`agentforge-datasource/spi`，已冻结）

```java
public interface SandboxProvider {
    SandboxResult execute(SandboxRequest request);
    String name();
    default List<String> supportedLanguages() { return List.of(); }
    default String description()     { return ""; }
}
public interface AvailabilityAware { boolean available(); }   // 内部接口，用来探测 docker

public record SandboxRequest(String language, String code, Duration timeout, Map<String,String> env) {}
public record SandboxResult(boolean success, String stdout, String stderr,
                            int exitCode, long elapsedMs, boolean truncated) {}
public record SandboxProviderInfo(String name, boolean available, List<String> languages, String description) {}
```

### 4.2 `ProcessSandboxProvider` 实现细节

| 项 | 实现 |
| --- | --- |
| 临时目录 | `agentforge.sandbox.dir`（默认 `./.data/sandbox`）下 `<uuid>/`，每个请求独立；`finally` 递归删除（先 `normalize()` 并校验 `startsWith(baseDir)` 防越界删除） |
| 语言 | `java` / `python` / `node` / `shell`，其它 → `不支持的语言: x（可选 [java, node, python, shell]）` |
| 代码长度 | ≤ 100000 字符（`MAX_CODE_LENGTH = 100_000`）；空代码拒绝 |
| 超时 | 请求值 clamp 到 `1~30s`；未传用 `agentforge.sandbox.timeout`（默认 10s）；超时 `killTree()` 后 `exitCode=-1` 且 stderr 追加 `[超时] 执行超过 Ns，进程已被强制终止` |
| 输出截断 | stdout / stderr 各 64KB（`MAX_OUTPUT_BYTES = 64 * 1024`），超出置 `truncated=true`，通过 daemon 线程边读边限流，避免管道写满卡死 |
| 环境变量 | 先 `environment.clear()`，再注入内核变量白名单 `PATH, JAVA_HOME, SYSTEMROOT, SYSTEMDRIVE, TEMP, TMP, LANG`；请求 `env` 只接受 `LANG, LC_ALL, TZ, PYTHONIOENCODING`，值 ≤ 1024 且不含换行/NUL |
| 禁用开关 | `agentforge.sandbox.enabled=false` → 直接返回失败「沙箱已禁用」 |
| 可用性 | `available()` = 开关打开且找到 java 可执行文件；`supportedLanguages()` 按 python/node 可执行文件是否存在动态给出（shell 恒定） |

各语言启动方式：

| 语言 | 文件 | 命令 |
| --- | --- | --- |
| java | `<PublicClass>.java`（正则识别 `public class Foo`，默认 `Main`） | `java -Dfile.encoding=UTF-8 <PublicClass>.java`（JDK 11+ 单文件源码模式，不生成 class 文件） |
| python | `main.py` | `AGENTFORGE_PYTHON` 环境变量 → PATH 中 `python`/`python3` → Windows 兜底 `C:\dev_env\miniconda\python.exe` |
| node | `main.js` | `AGENTFORGE_NODE` → PATH `node`；Windows 兜底 `C:\dev_env\node\node-24.14.0\node.exe` |
| shell | `main.ps1`（Windows）/ `main.sh` | Windows：`powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File main.ps1`；其它：`/bin/sh main.sh` |

超时强杀实现（进程树，避免子进程残留）：

```java
boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
if (!finished) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    process.waitFor(2, TimeUnit.SECONDS);
    // exitCode = -1，stderr 追加 [超时] 文案
}
```

### 4.3 Provider 选择与降级（`SandboxService`）

1. 注入 `List<SandboxProvider>`（Spring 收集所有实现）；
2. `execute` 时若有 `name()=="docker"` 且 `AvailabilityAware.available()` 为真 → 优先 Docker；
3. 否则回退 `process`；
4. 一个都没有 → 返回失败「当前环境无可用沙箱」，**不抛异常、不影响服务启动**（本机无 Docker 即走此路径，`providers` 里 docker `available=false`）。

`DockerSandboxProvider`（可选实现，默认关闭）真实参数：`docker run --rm --network none --memory 256m --cpus 0.5 -v <workDir>:/work -w /work <image> <cmd>`；镜像 `eclipse-temurin:21-jdk` / `python:3.12-slim` / `node:22-alpine` / `alpine:3.20`；探测 `docker version --format {{.Server.Version}}`（3s 超时）；开关 `agentforge.sandbox.docker-enabled`（默认 false）。

### 4.4 API 端点

| 方法/路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/sandbox/execute` | `datasource:query` | body `{language, code, timeoutSeconds?, env?}` |
| `GET /api/v1/sandbox/providers` | `datasource:read` | 各 provider 可用性与语言列表 |

```http
POST /api/v1/sandbox/execute
{"language":"java","code":"public class Main { public static void main(String[] a){ System.out.println(\"hi\"); } }","timeoutSeconds":5}

{"code":0,"data":{"success":true,"stdout":"hi\n","stderr":"","exitCode":0,"elapsedMs":504,"truncated":false}}
```

```http
GET /api/v1/sandbox/providers
{"code":0,"data":[
 {"name":"docker","available":false,"languages":["java","python","node","shell"],
  "description":"Docker 沙箱：--network none、内存 256MB、CPU 0.5；需 agentforge.sandbox.docker-enabled=true 且 Docker 可用"},
 {"name":"process","available":true,"languages":["java","python","node","shell"],
  "description":"本地进程沙箱：独立临时目录、超时 kill、stdout/stderr 各 64KB 截断、环境变量白名单"}]}
```

超时实测（python 死循环）：

```json
{"code":0,"data":{"success":false,"stdout":"","stderr":"[超时] 执行超过 3s，进程已被强制终止",
 "exitCode":-1,"elapsedMs":3018,"truncated":false}}
```

### 4.5 安全边界（**必读**）

`ProcessSandboxProvider` 是「**进程隔离 + 资源约束**」，**不是安全隔离**。它提供：

- 独立临时工作目录、执行后清理；
- 明确的解释器/入口文件（不经过 shell 拼接代码，杜绝命令注入）；
- 超时强杀进程树、输出截断、环境变量白名单。

它**不提供**（当前 v0.1.0 的真实边界）：

| 维度 | 现状 | 风险 |
| --- | --- | --- |
| 网络 | **无隔离**，代码可自由访问内网/外网 | 可探测内网端口、外联下载数据 |
| 文件系统 | **无隔离**，进程以 AgentForge 服务账号身份运行，可读写该账号有权访问的一切路径（含仓库目录、`application.yml`、`.env`、`.data`） | 可篡改配置/读取密钥文件 |
| 权限 | 无降权、无 seccomp/AppArmor、无 namespace/cgroup | 可 fork 炸弹、占满 CPU/内存 |
| 内存/CPU | Process 版**未设置** `-Xmx` 或 ulimit | 大内存代码可拖垮 JVM 所在主机 |
| 用户 | 与主服务同 UID | 逃逸即等于服务被控 |

**生产红线**：该接口只能在内网、已鉴权（`datasource:query`）、且不面向不可信用户的场景启用；公网/多租户场景必须使用 Docker 版（`--network none` + `--memory 256m` + `--cpus 0.5` + 只读根），或 gVisor / firejail / nsjail / 独立低权限主机。Docker 版当前已实现但默认关闭，**v0.2 计划**：默认启用 Docker（检测不到时显式报错而非静默回退 process）、增加 `--read-only`、`--pids-limit`、`--user 65534`、tmpfs 工作目录、镜像预拉取与白名单校验，并补 Docker 版集成测试。

本仓库不把「沙箱能执行任意代码」当作安全能力对外承诺；`docs/04-API契约.md` 中该接口的定位是「受控的代码片段试跑」。

### 4.6 如何新增一门语言

1. `ProcessSandboxProvider.SUPPORTED_LANGUAGES` 增加语言名（小写）；
2. `buildCommand()` 增加分支：写入口文件（如 `Main.rb`）并给出解释器命令；Windows/非 Windows 路径差异在此处理；
3. `supportedLanguages()`：若依赖外部可执行文件，按探测结果决定是否返回（参考 python/node 的 `findExecutable`）；
4. `SandboxExecuteRequest.language` 的 `@Pattern` 与 `docs/04-API契约.md` 的枚举同步更新（契约变更需先改文档）；
5. `SandboxExecuteRequest.toSandboxRequest()` 无需改动（超时/环境是通用逻辑）；
6. 补单测：正常执行、非法代码、超时 kill；
7. `DockerSandboxProvider` 若要支持同语言，同步加镜像与命令映射（否则 Docker 模式会返回「不支持的语言」）。

---

## 5. 二次开发指南

### 5.1 更换/新增切分策略

现状：`TextChunker` 是无状态静态工具类，策略硬编码为「段落聚合 + 512/64 + hardSplit」。要扩展：

1. 新增类（如 `MarkdownHeadingChunker`），签名 `List<String> split(String text)`；
2. 在 `DocumentIngestionService.ingest()` 中把 `TextChunker.split(text, 512, 64)` 替换为注入的策略 Bean（建议新建 `ChunkStrategies` 配置类，按 `application.yml` 的 `agentforge.rag.chunk-strategy` 选择）；
3. 同一知识库必须使用同一策略（否则历史块与新块语义不一致）；
4. 修改切分参数后需对存量文档执行 `reindex`，否则块边界不一致、检索质量下降；
5. 同步更新本文与被 `kb_chunk.char_count` 影响的统计口径。

### 5.2 新增/替换 embedding 模型

1. 实现 `org.springframework.ai.embedding.EmbeddingModel`（重写 `embed(String)`、`embed(Document)`、`dimensions()`、`call(EmbeddingRequest)` 即可）；
2. 若新增的 Bean 与原实现同时存在，用 `@Primary` 指定默认 + `@Qualifier` 限定注入点（`VectorStoreManager` 当前显式 `@Qualifier("hashingEmbeddingModel")`，替换时改这里）；
3. 维度必须与 `kb_knowledge_base.embedding_dimension` 一致（当前 1024）：换维度等于换索引进制，必须**新建知识库 + 重新上传**，不能复用旧 json；
4. 在线模型需把 Key 走 `CryptoUtils` 加密入库（模型配置表），并处理限流/超时降级（建议：`HashEmbeddingModel` 作为 `@ConditionalOnMissingBean` 兜底）；
5. 同一知识库内**不允许混用**不同模型：`kb.embedding_model_id` 已在表中预留，落地时应在 upload/search 时校验。

### 5.3 新增数据源类型

1. `DataSourceType` 枚举加一项：`POSTGRESQL("jdbc:postgresql:", "org.postgresql.Driver")` 这种形式（`urlPrefix` + 驱动类）；
2. 在 `agentforge-app/pom.xml` 加对应驱动（`runtime` scope，版本进根 `pom.xml` 的 `dependencyManagement`）；
3. `DataSourceRequest` 的 `@Pattern` 与契约文档同步（当前只允许 `MYSQL|POSTGRESQL|H2`）；
4. 无需改表（`type` 是 VARCHAR(16)），无需改 `SqlGuard`（只读校验方言无关，JSqlParser 解析的是通用 SQL）；
5. 验证：`test` 连接、`schema`（`DatabaseMetaData` 方言差异）、`query` 三条链路；补一条该类型的集成测试。

### 5.4 接入 Docker 沙箱（生产化）

1. 部署机安装 Docker，确认 `docker version` 对 AgentForge 运行账号可用（或配置 `DOCKER_HOST`）；
2. 设置 `agentforge.sandbox.docker-enabled=true` —— `SandboxService` 会优先选 docker，`providers` 接口会显示 `available=true`；
3. 预拉镜像：`eclipse-temurin:21-jdk`、`python:3.12-slim`、`node:22-alpine`、`alpine:3.20`；生产建议改为内网镜像仓库并固定 digest；
4. 加固项（v0.2 计划落地，需改 `DockerSandboxProvider` 的 `docker run` 参数）：`--read-only`、`--pids-limit 64`、`--user 65534:65534`、`--tmpfs /tmp:size=16m`、`--cap-drop ALL`、`--security-opt no-new-privileges`；
5. 监控：容器退出码、`truncated`、`elapsedMs` 落日志；镜像拉取超时会拖慢首次执行，建议启动时预热；
6. 保持 `ProcessSandboxProvider` 仅为本地开发兜底，不要在公网把 `process` 设为唯一 provider。

---

## 6. 配置项一览（真实键名）

| 键 | 默认值 | 被谁读取 | 说明 |
| --- | --- | --- | --- |
| `agentforge.rag.upload-dir` | `./.data/rag-uploads` | `DocumentService` | 原始文件落盘根目录 |
| `agentforge.rag.vector-store-dir` | `./.data/vector-store` | `VectorStoreManager` | 向量 json 目录 |
| `agentforge.sandbox.enabled` | `true` | `ProcessSandboxProvider` | 关闭后 execute 直接失败 |
| `agentforge.sandbox.dir` | `./.data/sandbox` | 两个 provider | 临时目录根 |
| `agentforge.sandbox.timeout` | `10s` | `ProcessSandboxProvider` | 默认超时（请求可覆盖，clamp 1~30s） |
| `agentforge.sandbox.docker-enabled` | `false` | `DockerSandboxProvider` | true 且探测到 docker 时优先 |
| `agentforge.crypto.key` | dev 默认值 | `CryptoUtils` | AES-GCM 主密钥，生产用 `AGENTFORGE_CRYPTO_KEY` 覆盖 |
| `agentforge.rag.chunk-size` / `chunk-overlap` / `top-k` | 800 / 120 / 5 | **当前无人读取** | 见 L2，代码用常量 512/64 |

---

## 7. 已知限制（v0.1.0 真实状态）

| 编号 | 限制 | 影响 | 计划 |
| --- | --- | --- | --- |
| L1 | 哈希 embedding 只做词法近似，无真正语义泛化（同义词/改写句命中率低） | 检索质量上限受算法限制 | v0.2 接入在线 embedding（接口与维度字段已预留） |
| L2 | `application.yml` 的 `agentforge.rag.chunk-size/chunk-overlap/top-k` **未被读取**，实际是 `TextChunker` 常量 512/64 与请求默认 topK=5 | 改 yml 不生效，易误解 | 引入 `@ConfigurationProperties` 绑定（需与契约/文档同步） |
| L3 | Agent 工具 `knowledge_search` 不传 `knowledgeBaseId` 时调用 `retrieveAll(List.of())`，`KnowledgeRetrievalService` 对空列表直接返回空 | 「检索全部知识库」当前不可用，需显式传库 ID | `retrieveAll` 空列表时回退为枚举全部 KB，或维护全局索引 |
| L4 | 检索不支持元数据过滤参数（SPI 只有 query/topK/threshold；`Filter.Expression` 仅用于删除） | 无法按 `documentId/filename` 限定检索 | SPI 扩展 `filter`（需改契约 → 走冻结流程） |
| L5 | 向量库为进程内 `SimpleVectorStore` + 单文件 JSON，全量加载进内存 | 多实例不共享、重启需加载、超大知识库内存占用高 | v0.2 可切 PGVector/Milvus（`VectorStore` 抽象已隔离） |
| L6 | 上传是「先落盘、再异步」；异步失败只置 `FAILED`，无自动重试与进度条 | 大批量上传需人工 `reindex` | 引入重试队列/任务表 |
| L7 | `@Lob` 换成 `columnDefinition="TEXT"` 后，换库（如 PostgreSQL 需 `text`）迁移要复核 | 方言耦合 | 迁移按方言拆分或统一用 TEXT |
| L8 | 数据源仅 MySQL/H2 驱动随包（`POSTGRESQL` 类型存在但驱动缺失） | 选 POSTGRESQL 会「驱动未安装」 | 加 `org.postgresql:postgresql` 依赖 |
| L9 | `SqlGuard` 基于正则 + JSqlParser 单语句，不是数据库侧只读账号/权限 | 目标库账号若权限过大，绕过风险取决于 DB 授权 | 生产强制使用只读 DB 账号（文档要求），v0.2 增加账号权限校验 |
| L10 | `ds_query_log.sql_text` 保存完整 SQL 字面量（含可能敏感值），仅在 4000 字符截断，未做值级脱敏 | 审计需要 vs 敏感数据 | 提供可配置脱敏（值替换为 `?`） |
| L11 | 查询结果列值统一转字符串（非原始类型），`bigint` 等以文本返回 | 前端需自行解析 | 契约层面约定，v0.2 可加类型元数据 |
| L12 | 沙箱 Process 版无网络/文件系统/内存隔离（见 4.5） | 只适合内网受控场景 | v0.2 Docker 默认 + 硬化参数 |
| L13 | 沙箱临时目录清理失败会残留（`finally` 尽力删除，异常被忽略） | 磁盘残留 | 增加定期清理任务 |
| L14 | Docker 沙箱无集成测试（本机无 Docker），仅单测覆盖 Process 版 | Docker 路径回归风险 | CI 起 docker-in-docker 后补 |
| L15 | 沙箱执行的权限码复用 `datasource:query`（契约如此） | 权限语义与「查库」耦合 | 契约变更后拆 `sandbox:execute` |
| L16 | 知识库名称无唯一约束；`embedding_model_id` 仅存不用 | 可能重名/模型不一致 | v0.2 加唯一校验与模型校验 |

---

## 8. 验证记录（v0.1.0）

| 项 | 结果 |
| --- | --- |
| 单测 `agentforge-rag` | 12 个通过（`TextChunkerTest` 5、`HashingEmbeddingModelTest` 7） |
| 单测 `agentforge-datasource` | 45 个通过（`SqlGuardTest` 39 条表驱动恶意 SQL、`ProcessSandboxProviderTest` 6：java 执行、超时 kill、非法语言、超大代码、env 白名单、可用性） |
| Flyway | V1/V2/V4/V5/V6 在 H2(MODE=MySQL) 全部通过，`ddl-auto=validate` 通过 |
| 端到端（18080/18099 真实 HTTP） | 建库→上传→READY→search 命中（score 0.2085）；datasource test/`SELECT 1`/`SELECT demo_user` 通过，`DELETE`/多语句 400 被拒，query-logs 记录自动 `LIMIT 10`；sandbox java/python 输出 hi，python 死循环 3s 超时 kill；`providers` 显示 docker 不可用、process 可用 |
| 证据文件 | `.data/e2e-t3-results.txt`（本地运行产物，未入库） |

---

## 9. 变更记录

| 版本 | 日期 | 内容 |
| --- | --- | --- |
| v0.1.0 | 2026-10-29 | 首版：RAG 管线/数据源只读查询/进程沙箱的实现说明、安全边界与已知限制（对应 commit `ed2476a`、`d1e3437`） |
