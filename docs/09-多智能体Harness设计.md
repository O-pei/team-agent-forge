# 09 · 多智能体 Harness 设计（agentforge-agent）

> 本文描述 `agentforge-agent` 模块的**真实实现**（Spring AI Alibaba 1.1.2.4 · Spring AI 1.1.2 · JDK 21），
> 而不是目标形态；与目标形态的差异写在「已知限制 / v0.2 计划」中。
> 相关契约以 [`04-API契约.md`](04-API契约.md) §2.6 / §3 / §5 为准，本文只解释实现。

| 项目 | 值 |
| --- | --- |
| 模块 | `agentforge-agent` |
| 核心依赖 | `agentforge-audit`（模型工厂 + Token 审计）、`agentforge-memory`、`agentforge-rag`、`agentforge-datasource`、`agentforge-security` |
| 智能体框架 | Spring AI Alibaba `1.1.2.4-security-fix`（`agent-framework` + `graph-core`） |
| 数据库迁移 | `agentforge-app/src/main/resources/db/migration/V6__agent.sql`（7 张表） |
| 对外接口 | `/api/v1/agents`、`/api/v1/chat/stream`、`/api/v1/runs/*`、`/api/v1/tools`、`/api/v1/orchestrations` |
| 权限码 | `agent:read`（读）、`agent:write`（写）、`agent:run`（运行/对话/取消/审批） |

模块内包结构：

```
com.agentforge.agent
├── controller   AgentController / ChatController / RunController / ToolController / OrchestrationController
├── service      AgentService（CRUD+版本）/ AgentExecutionService（运行生命周期）
│                AgentChatService（SSE）/ AgentRunService（非流式运行）/ OrchestrationService
├── runtime      ReactAgentFactory / JacksonSafeChatModel / RunModelInterceptor / RunToolInterceptor
│                RunContext / RunContextHolder / RunRegistry / AgentEventSink
├── tool         AgentToolRegistry + 7 个 AgentToolDefinition 实现
├── entity       7 个 JPA 实体（继承 common 的 BaseEntity）
├── repository   对应 Spring Data JPA Repository
├── dto          请求/响应 record
└── event        AgentEvent（事件名常量）+ AgentEvents（各类 data 负载 record）
```

---

## 1. 架构：从数据库配置到可运行 Agent

### 1.1 一次运行的完整链路

```
ChatController.stream / RunController.run
        │  ChatStreamRequest / RunRequest
        ▼
AgentChatService.chat（SSE） / AgentRunService.run（非流式）
        │
        ▼
AgentExecutionService.start
   1. 校验 message、加载 Agent 定义（tenant 隔离，软删除过滤，enabled 校验）
   2. 生成/复用 sessionId → upsert agent_session
   3. 创建 agent_run（status=RUNNING, trace_id, started_at, tokens=0）
   4. 保存 user 消息到 agent_message
   5. new RunContext(runId, agentId, sessionId, tenantId, userId, streaming, sink)
   6. ReactAgentFactory.build(definition, runContext)  → SAA Agent
   7. RunRegistry.register(context)，回写 run.model_name
   8. RunnableConfig.builder().threadId(sessionId).build()
        │
        ▼
bindRunThread(execution)        // RunContextHolder.set + UsageContext.open(...)
        │
        ├─ SSE：ReactAgent.stream(message, config) 逐块 emit message.delta
        └─ 非流式：ReactAgent.call(message, config) / FlowAgent.invokeAndGetOutput(...)
        │
        ▼
AgentExecutionService.complete(execution, answer, status, error)
   - 回写 agent_run（status/output/tokens/model/finished_at/error_message）
   - persistSteps：agent_run_step 先 delete by run_id 再逐条写入
   - 保存 assistant 消息 + agent_session.message_count += 2
   - finally：RunRegistry.unregister / UsageContext.close / RunContextHolder.clear
```

### 1.2 `ReactAgentFactory` 构建链（`runtime/ReactAgentFactory.java`）

```java
// 1) 模型：ModelProviderFactory → UsageRecordingChatModel（audit 包装）→ JacksonSafeChatModel
private ChatModel resolveChatModel(AgentDefinitionEntity definition, RunContext context) {
    ModelProviderFactory factory = modelProviderFactoryProvider.getIfAvailable();
    ChatModel model = null;
    if (factory != null) {
        model = definition.getModelId() == null
                ? factory.getDefaultChatModel() : factory.getChatModel(definition.getModelId());
        ChatModelInfo info = ...;                 // 记录 modelName 供 SSE/run 落库
        context.setModelName(info.modelName());
    }
    if (model == null) {                          // 无实现或解析异常 → 降级 Mock，保证零配置可用
        model = new MockChatModel("mock-chat");
        context.setModelName("mock-chat");
    }
    return JacksonSafeChatModel.wrapIfNecessary(model);
}
```

`ModelProviderFactory`（audit 模块）返回的 ChatModel 已被 `UsageRecordingChatModel` 包装，因此
**每次模型调用都会自动生成 `af_token_usage` 记录**，归属由 `UsageContext` 决定（见 §6）。

`JacksonSafeChatModel` 解决了一个框架缺陷：SAA `DefaultBuilder.build()` 用
`ModelOptionsUtils.merge` 合并 ChatOptions，而 Spring AI 的 `DefaultChatOptions` 没有
`@JsonProperty` 字段会抛 `IllegalArgumentException: No @JsonProperty fields found`（Mock 模型默认触发）。
该包装器用反射检测底层默认选项是否"Jackson 可序列化"，不满足则换成 `DashScopeChatOptions`，
`call/stream/getDefaultOptions` 全部委托给真实模型：

```java
public static ChatModel wrapIfNecessary(ChatModel model) {
    if (model == null || model instanceof JacksonSafeChatModel) return model;
    if (hasJacksonAnnotatedOptions(model.getDefaultOptions())) return model; // 反射扫描 @JsonProperty
    return new JacksonSafeChatModel(model);
}
```

`buildReactAgent` 的装配顺序（SINGLE 类型）：

```java
Builder builder = ReactAgent.builder()
        .name(agentName(definition))
        .description(nullToEmpty(definition.getDescription()))
        .model(model)
        .saver(new MemorySaver())                                  // 无 Redis 环境
        .interceptors(List.of(new RunModelInterceptor(context),    // 记录 MODEL 步骤 + 累计 usage
                              new RunToolInterceptor(context, toolRegistry))); // tool.call/tool.result + TOOL 步骤

if (systemPrompt != null && !systemPrompt.isBlank()) builder.systemPrompt(systemPrompt);
List<Object> toolBeans = toolRegistry.resolve(toolNames(definition));  // 未注册工具直接 40000
if (!toolBeans.isEmpty()) builder.methodTools(toolBeans.toArray());    // @Tool Bean
builder.hooks(List.of(ModelCallLimitHook.builder().runLimit(maxIterations).build())); // 迭代上限
if (temperature != null) {
    DashScopeChatOptions options = new DashScopeChatOptions();   // DefaultChatOptions 不可序列化
    options.setTemperature(temperature);
    builder.chatOptions(options);
}
if (outputType != null) builder.outputType(Class.forName(outputType));
return builder.build();
```

| 配置字段（`agent_definition`） | 作用点 | 缺省行为 |
| --- | --- | --- |
| `model_id` | `ModelProviderFactory.getChatModel/getDefaultChatModel` | 空 → 默认模型；异常/无工厂 → `MockChatModel("mock-chat")` |
| `system_prompt` | `Builder.systemPrompt` | 空则不下发 |
| `tools_json` | `AgentToolRegistry.resolve` → `Builder.methodTools` | 空 → 无工具；未注册工具 → 40000 |
| `max_iterations` | `ModelCallLimitHook.runLimit` | DB DEFAULT 5；工厂兜底 10；Service 写入时兜底 5 |
| `temperature` | `DashScopeChatOptions.setTemperature` | 空则用模型默认 |
| `output_type` | `Builder.outputType(Class.forName(...))` | 空/类不存在 → 忽略（告警日志） |
| `sub_agent_ids_json` | 递归 `loadSubAgents` | flow 类型必须有子 Agent（校验见 §4） |

> 深度保护：子 Agent 递归构建上限 `MAX_DEPTH = 3` 层，超出抛 `BizException.badRequest`。

### 1.3 运行上下文与可观测性

`RunContext`（`runtime/RunContext.java`）是一次运行的**可变句柄**，持有：

| 字段 | 用途 |
| --- | --- |
| `runId/agentId/sessionId/tenantId/userId` | 事件与审计归属 |
| `streaming` | 流式/非流式标记 |
| `AgentEventSink sink` | SSE 推送函数；推送失败置 `cancelled`，避免继续写已关闭连接 |
| `AtomicReference modelName` | 实际模型名（SSE `run.started.model`、`agent_run.model_name`） |
| `LongAdder prompt/completion/totalTokens` | 由拦截器与 `NodeOutput.tokenUsage()` 累计，用于 `usage`/`run.completed` |
| `AtomicBoolean cancelled` | cancel 接口/超时/sink 失败时置位，流上 `takeWhile(node -> !cancelled)` |
| `CopyOnWriteArrayList<StepRecord> steps` | 运行步骤，结束时落 `agent_run_step` |

`RunRegistry` 以 `runId → RunContext` 支持取消；`cancel` 只对**本机正在运行**的 run 生效，
返回 `false` 时仅记录日志（非本机实例执行）。`RunContextHolder` 是 ThreadLocal，
运行线程在 `bindRunThread` 中绑定、在 `complete` 的 finally 中清理；请求线程（Tomcat）不绑定，避免串线程。

---

## 2. 数据模型（V6 真实迁移）

`V6__agent.sql` 共 7 张表，全部 MySQL 8 / H2（`MODE=MySQL`）兼容；除日志表外均含
`created_at/updated_at/version`（`BaseEntity` 维护），业务版本号使用 `version_no` 以避免与乐观锁 `version` 冲突。

### 2.1 `agent_definition`：Agent 定义（当前配置）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AI | |
| `tenant_id` | BIGINT NOT NULL DEFAULT 1 | 租户隔离，`(tenant_id, name)` 唯一 |
| `name` / `description` | VARCHAR(64) / VARCHAR(512) | 名称唯一（软删除行仍占用名称） |
| `type` | VARCHAR(16) NOT NULL DEFAULT 'SINGLE' | SINGLE/SEQUENTIAL/PARALLEL/ROUTING/SUPERVISOR |
| `system_prompt` | TEXT | 系统提示词 |
| `model_id` | BIGINT NULL | 关联 audit 模型；空=默认模型 |
| `temperature` | DOUBLE NULL | |
| `max_iterations` | INT NOT NULL DEFAULT 5 | `ModelCallLimitHook.runLimit` |
| `tools_json` | TEXT | 工具名 JSON 数组，如 `["calculator","current_time"]` |
| `sub_agent_ids_json` | TEXT | 子 Agent id JSON 数组（按顺序执行） |
| `output_type` | VARCHAR(255) | 结构化输出类名（`Class.forName`） |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE | 禁用后运行返回 40000 |
| `current_version` | INT NOT NULL DEFAULT 1 | 每次 update +1 |
| `published_version` | INT NULL | publish 后指向被发布版本 |
| `deleted` | BOOLEAN NOT NULL DEFAULT FALSE | 软删除；查询一律过滤 |

### 2.2 `agent_version`：配置快照（不可变历史）

| 列 | 说明 |
| --- | --- |
| `agent_id` + `version_no` | 唯一键 `uk_agent_version_agent_no` |
| `config_json` | 该版本的完整 `AgentResponse` JSON 快照 |
| `published` | 是否被发布（同一 Agent 可有多个历史 published 行） |

版本语义：**create → v1；每次 update → current_version + 1 并插入一条新 version 行**；
`publish(id, version)` 将指定版本行 `published=true`，并把 `agent_definition.published_version` 指向它。
运行使用 `current_version`，而不是 published 版本（简化实现，见 §8 限制）。

### 2.3 `agent_run`：一次运行（状态机）

```
         start()                 complete(SUCCEEDED)      ← 正常结束
RUNNING ─────────► RUNNING ────► SUCCEEDED
   │                   │
   │                   ├───────► FAILED               ← 异常（SSE 先发 error 再发 run.completed）
   │                   └───────► CANCELLED            ← cancel/超时/sink 失败后流结束
   │
   └─ 服务重启/实例崩溃：记录停留 RUNNING（无自动回收，见 §8 限制）
```

| 列 | 说明 |
| --- | --- |
| `status` | RUNNING / SUCCEEDED / FAILED / CANCELLED（`RunStatus` 常量） |
| `input` / `output` | 用户输入 / 最终回答（answer） |
| `agent_version` | 启动时的 `current_version` |
| `session_id` / `user_id` / `tenant_id` | 归属 |
| `prompt_tokens/completion_tokens/total_tokens` | 运行结束时的 `RunContext.usageSnapshot()` |
| `model_id/model_name` | 实际模型（ModelProviderFactory 解析结果） |
| `error_message` | ≤1000 字符 |
| `trace_id` | `TraceContext.currentOrCreate(...)`，与请求/MDC 对齐 |
| `started_at/finished_at` | `elapsedMs` 由两者差值计算（`GET /runs/{id}`） |

### 2.4 `agent_run_step`：运行步骤（可解释性）

| 列 | 说明 |
| --- | --- |
| `run_id` + `seq_no` | 同一运行内单调整数（`RunContext.addStep` 分配） |
| `step_type` | `MODEL` / `TOOL` / `APPROVAL` |
| `agent_name` / `node_name` | 预留（当前 MODEL/TOOL 步骤未填） |
| `tool_name` | TOOL 步骤的工具名 |
| `input` / `output` | 截断 4000 字符；MODEL 步骤为 prompt 摘要 / 回答文本 |
| `status` | RUNNING / SUCCEEDED / FAILED |
| `elapsed_ms` | 拦截器计时 |

落库规则（`AgentExecutionService.persistSteps`）：**结束时先 `deleteByRunId` 再逐条 insert**，
保证重试/重放不产生重复步骤；`seq_no` 为 0 时按列表顺序补齐。

流式 MODEL 步骤的真实文本回填（提交 `110a5fb`）：拦截器只能拿到 `Publisher`/`Flux`，
因此先写入占位符 `RunModelInterceptor.STREAM_PLACEHOLDER = "[流式输出]"`；
`AgentChatService.runStream` 在拿到完整 answer 后调用 `RunContext.resolveStreamOutput(finalAnswer)`，
把**最后一个占位**的 MODEL 步骤 output 替换为真实文本，再发 `message.completed` 并落库。
实测 `GET /runs/1` 的 steps[0] 已是真实文本（例如
`"output":"（Mock 模型）已收到你的消息：请用一句话介绍你自己。这是模拟的流式输出。"`），不再是 `FluxHandle`。

### 2.5 `agent_session` / `agent_message`：会话与消息

| 表 | 关键列 | 说明 |
| --- | --- | --- |
| `agent_session` | `session_id` 唯一、`agent_id`、`user_id`、`title`（首条消息前 60 字）、`message_count` | 运行结束时 `message_count += 2`（user + assistant） |
| `agent_message` | `session_id`（索引）、`run_id`、`role`（user/assistant）、`content`（≤100000）、`tool_calls_json` | 对话审计与后续前端回放 |

> 当前实现：`start()` 只在 `sessionId` 为空时创建会话，不存在的 `sessionId` 也会被创建（宽松）；
> assistant 消息在 `complete()` 中写入，失败/取消运行不写 assistant 消息。

### 2.6 `agent_orchestration`：编排配置

| 列 | 说明 |
| --- | --- |
| `(tenant_id, name)` 唯一 | 编排名称 |
| `type` | 白名单：SEQUENTIAL / PARALLEL / ROUTING / SUPERVISOR（`OrchestrationService.TYPES`） |
| `config_json` | 编排参数，由调用方自定义（v0.1 仅存取，不参与运行装配） |
| `enabled` | 启用标记 |

`/api/v1/orchestrations` 目前是**独立配置 CRUD**：运行入口仍是 `agent_definition.type`，
编排表用于前端保存"编排方案"、后续版本再与运行引擎打通（见 §8）。

---

## 3. Agent 类型与编排实现

`AgentType`（`domain/AgentType.java`）与真实实现一一对应：

| 类型 | SAA 实现类 | 真实装配 | 子 Agent 要求 | 输出提取 |
| --- | --- | --- | --- | --- |
| `SINGLE` | `ReactAgent` | 模型 + systemPrompt + methodTools + 两个拦截器 + `ModelCallLimitHook` + `MemorySaver` | 无 | `call()` 返回 `AssistantMessage.getText()` |
| `SEQUENTIAL` | `SequentialAgent` | `.subAgents(loadSubAgents(...)).saver(MemorySaver)` | ≥1（Service 校验） | `invokeAndGetOutput` → `extractText` |
| `PARALLEL` | `ParallelAgent` | `.subAgents(...).maxConcurrency(n).mergeOutputKey("merged_results")` | **≥2**（SAA 运行时强制） | `extractText` 优先取 `merged_results` |
| `ROUTING` | `LlmRoutingAgent` | 路由模型 + systemPrompt + subAgents | ≥1 | `extractText` |
| `SUPERVISOR` | `LlmRoutingAgent`（近似） | 主管 systemPrompt + `fallbackAgent(第一个子 Agent 的 name)` | ≥1 | `extractText` |

### 3.1 `SUPERVISOR` 的诚实说明

**SAA 1.1.2.4 没有 `SupervisorAgent`**（只有 `SequentialAgent` / `ParallelAgent` / `LlmRoutingAgent` / `LoopAgent`），
所以当前 `SUPERVISOR` 用 `LlmRoutingAgent` 近似：

```java
case SUPERVISOR -> buildSupervisor(definition, context, model, saver, depth);

private Agent buildSupervisor(AgentDefinitionEntity definition, RunContext context,
                              ChatModel model, MemorySaver saver, int depth) {
    List<Agent> subAgents = loadSubAgents(definition, context, depth);
    if (subAgents.isEmpty()) throw BizException.badRequest("SUPERVISOR 类型至少需要配置一个子 Agent");
    return LlmRoutingAgent.builder()
            .name(agentName(definition))
            .description(nullToEmpty(definition.getDescription()))
            .model(model)
            .systemPrompt(orDefault(definition.getSystemPrompt(),
                    "你是主管 Agent，负责把用户任务分派给最合适的子 Agent 执行，并汇总结果回复用户。"))
            .subAgents(subAgents)
            .fallbackAgent(subAgents.getFirst().name())
            .saver(saver)
            .build();
}
```

能力边界：路由型主管**只做一次分派**（选择子 Agent 或 fallback），不具备"多轮反思—再分派—汇总"的
监督循环，也不具备任务拆解。因此：

- v0.1 建议把 SUPERVISOR 当作「单次分派 + 结果返回」使用；
- **v0.2 计划**用 `graph-core` 的 `StateGraph` 显式编排「主管节点 → 条件边 → 子 Agent 节点 → 回到主管」循环，
  配合 `MemorySaver` 的 checkpoint 实现真正的监督者语义（不走 SAA 预置 flow agent）。

> 同一处偏差也适用于 `ROUTING`：`LlmRoutingAgent` 是"一次路由"，不是多轮路由。

### 3.2 子 Agent 装配

`subAgentIds` 来自 `agent_definition.sub_agent_ids_json`，`loadSubAgents`：

1. 按 id 批量查询（`findByIdInAndTenantIdAndDeletedFalse`），**保持配置顺序**（不依赖数据库返回顺序）；
2. 缺失/跨租户/已删除的子 Agent 跳过并告警（不中断）；
3. 递归调用 `build(subDefinition, context, depth + 1)`，共享同一个 `RunContext`
   （子 Agent 的 MODEL/TOOL 步骤也记录到父 run，`steps` 中 `seq_no` 全 run 单增）；
4. 深度 > 3 抛 `BizException.badRequest`。

非 SINGLE 的 flow agent 不注入 `RunModelInterceptor`/`RunToolInterceptor`（SAA flow agent 的构建器不暴露
`interceptors`），子 Agent 若是 SINGLE 仍带拦截器，因此编排运行的步骤来自子 Agent 自身。

### 3.3 执行与状态落库时序

| 阶段 | 非流式（`POST /agents/{id}/run`） | 流式（`POST /api/v1/chat/stream`） |
| --- | --- | --- |
| 启动 | `start(..., streaming=false, sink=null)` | `start(..., streaming=true, sink=SSE)` |
| 执行 | 请求线程 `bindRunThread` → `call()/invoke` | 线程池 `agentRunExecutor` 中 `bindRunThread` → `stream()` |
| 事件 | 无 | `run.started → message.delta×N → message.completed → usage → run.completed` |
| 结束 | `complete(SUCCEEDED)` 后直接返回 `RunResponse` | `complete(...)` → 发 `run.completed` → `emitter.complete()` |
| 失败 | `complete(FAILED)` → 抛 `BizException.badRequest("Agent 运行失败: ...")` | 发 `error{code,message}` → `complete(FAILED)` → 发 `run.completed(FAILED)` |
| 取消 | `POST /runs/{id}/cancel` 置 flag（本机） | 流上 `takeWhile(!cancelled)` 停止 → `complete(CANCELLED)` |

`extractText(NodeOutput)`（`AgentExecutionService`）按以下顺序提取最终文本：
`state.value("messages")` 中最后一个 `AssistantMessage` → `state.data()["output"]` → `state.data()["merged_results"]`。
这是 flow agent 输出的统一出口（Mock 模型下也适用）。

---

## 4. 工具集

工具 = 一个带 `@Tool` 方法的 Spring Bean + `AgentToolDefinition` 元数据（name/description/riskLevel/toolBean）。
`AgentToolRegistry` 汇总所有实现，`/api/v1/tools` 返回元数据，`ReactAgentFactory` 通过 `methodTools` 注入 Bean。

### 4.1 工具清单（`GET /api/v1/tools` 实测 7 个）

| 工具名 | 风险 | 入参（`@ToolParam`） | 依赖 SPI | 无实现时 |
| --- | --- | --- | --- | --- |
| `calculator` | LOW | `expression: String`（必填，支持 `+ - * / %` 与括号） | 无（手写递归下降解析） | — |
| `current_time` | LOW | `zone: String`（可选，IANA 时区） | 无 | — |
| `knowledge_search` | LOW | `knowledgeBaseId: Long`（可选）、`query: String`（必填）、`topK: Integer`（可选，默认 5，最大 20） | `KnowledgeRetriever`（rag） | 返回降级提示文本 |
| `recall_facts` | LOW | `query: String`（必填）、`topK: Integer`（可选，默认 5，最大 20） | `LongTermMemory`（memory） | 返回降级提示文本 |
| `remember_fact` | MEDIUM | `fact: String`（必填）、`category: String`（可选，默认 general） | `LongTermMemory`（memory） | 返回降级提示文本 |
| `sql_query` | HIGH | `datasourceId: Long`（必填）、`sql: String`（必填，只读）、`maxRows: Integer`（可选，默认 100，最大 500） | `SqlQueryService`（datasource） | 返回降级提示文本 |
| `code_execute` | HIGH | `language: String`（java/python/node）、`code: String` | `SandboxProvider`（datasource） | 返回降级提示文本 |

风险等级用于两处：SSE `tool.call.riskLevel`（前端审批提示）、`/api/v1/tools` 展示。
**MEDIUM/HIGH 工具目前只做展示与事件标注，不强制拦截**；HITL 审批见 §8。

### 4.2 实现示例（`tool/KnowledgeSearchTool.java`）

```java
@Tool(name = ToolNames.KNOWLEDGE_SEARCH,
        description = "从企业知识库检索与问题相关的文档片段，返回片段内容、来源文件与相关度")
public String knowledgeSearch(
        @ToolParam(description = "知识库 ID；不传则检索全部知识库", required = false) Long knowledgeBaseId,
        @ToolParam(description = "检索问题或关键词") String query,
        @ToolParam(description = "返回条数，默认 5，最大 20", required = false) Integer topK) {
    KnowledgeRetriever retriever = retrieverProvider.getIfAvailable();
    if (retriever == null) return "知识检索服务尚未配置（RAG 模块未就绪），请改用其他方式回答或稍后重试。";
    int limit = topK == null || topK <= 0 ? 5 : Math.min(topK, 20);
    List<RetrievedChunk> chunks = knowledgeBaseId == null
            ? retriever.retrieveAll(List.of(), query, limit, 0.5d)
            : retriever.retrieve(knowledgeBaseId, query, limit, 0.5d);
    RunContext runContext = RunContextHolder.current();
    for (RetrievedChunk chunk : chunks) {
        // 命中一条 chunk 即推送 citation（index 从 1 开始），同时放入 JSON 返回给模型
        if (runContext != null) {
            runContext.emit(AgentEvent.CITATION, new AgentEvents.Citation(
                    runContext.nextCitationIndex(), chunk.documentId(), chunk.filename(),
                    chunk.chunkIndex(), chunk.score()));
        }
    }
    ...
}
```

### 4.3 与各模块 SPI 的关系

| 工具 | SPI（模块） | 方法 | 降级 |
| --- | --- | --- | --- |
| `knowledge_search` | `com.agentforge.rag.spi.KnowledgeRetriever` | `retrieve(kbId,query,topK,threshold)` / `retrieveAll(kbIds,query,topK,threshold)` → `RetrievedChunk` | `ObjectProvider.getIfAvailable()` 为空 → 提示文本 |
| `remember_fact` / `recall_facts` | `com.agentforge.memory.spi.LongTermMemory` | `saveFact(tenant,user,fact,category,confidence)` / `recall(tenant,user,query,limit)` / `deleteFact` | 同上 |
| `sql_query` | `com.agentforge.datasource.spi.SqlQueryService` | `query(datasourceId, sql, maxRows)` → `SqlQueryResult` | 同上 |
| `code_execute` | `com.agentforge.datasource.spi.SandboxProvider` | `execute(SandboxRequest(language,code,timeout,env))` → `SandboxResult` | 同上 |

**所有外部依赖都用 `ObjectProvider` 注入**，模块未就绪时工具仍可注册、可被模型调用，只是返回降级提示，
保证 App 在 P1~P7 并行开发期间始终能启动。审计（Token）不依赖工具，走模型包装器。

### 4.4 SAA 工具签名限制（踩坑记录）

SAA 1.1.2.4 通过 Spring AI `ToolCallbacks.from(...)` 反射解析 `@Tool` 方法，限制如下：

| 限制 | 说明 | 本模块对策 |
| --- | --- | --- |
| 不支持原始类型入参（`long`/`int`/`double`） | 反序列化失败 | 一律用包装类型 `Long`/`Integer`/`Double` |
| 不支持 `Optional<T>` 入参 | 反射无法生成 schema | 用可空包装类型 + 代码内默认值 |
| 不支持集合/Map/复杂对象入参 | JSON Schema 生成失败或乱码 | 用 `String` 承接 JSON，工具内 `ObjectMapper` 解析 |
| 不支持 `CompletableFuture`/响应式返回值 | 返回值需同步 | 工具方法同步返回 `String`（或 `@Tool` 支持的标量） |
| 参数名依赖 `-parameters` 编译 | 缺省编译会得到 `arg0`，模型无法按名传参 | 根 pom 已开 `-parameters`（Boot parent 默认）+ `@ToolParam(description=...)` 显式描述 |

因此本模块 7 个工具**全部**使用 `String`/`Long`/`Integer` 标量入参、同步 `String` 返回值。

---

## 5. HTTP 接口与 SSE 协议

### 5.1 Agent CRUD 与版本（`AgentController`，`/api/v1/agents`）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/agents` | `agent:read` | 分页+关键字：`page`（默认 1）、`size`（默认 10，≤100）、`keyword`；返回 `PageResult<AgentSummary>` |
| GET | `/api/v1/agents/{id}` | `agent:read` | `AgentResponse`（含 tools/subAgentIds/currentVersion/publishedVersion） |
| POST | `/api/v1/agents` | `agent:write` | `AgentCreateRequest`；名称重复 409，工具未注册 400 |
| PUT | `/api/v1/agents/{id}` | `agent:write` | `AgentUpdateRequest`（全部可空，非 null 才覆盖），**生成新版本** |
| DELETE | `/api/v1/agents/{id}` | `agent:write` | 软删除（`deleted=true`） |
| POST | `/api/v1/agents/{id}/publish` | `agent:write` | body `{"version":1}` 可空（空=当前版本） |
| GET | `/api/v1/agents/{id}/versions` | `agent:read` | 版本列表（新→旧，含 `configJson` 快照） |

创建示例：

```json
POST /api/v1/agents
{
  "name": "自我介绍助手",
  "type": "SINGLE",
  "systemPrompt": "你是一个简洁的中文助手",
  "modelId": 1,
  "temperature": 0.3,
  "maxIterations": 4,
  "tools": ["current_time", "calculator"],
  "enabled": true
}
```

校验规则（`AgentService.validateRequest`）：名称非空且租户内唯一；`type` 必须在枚举内；
`tools` 必须全部已注册；`type.isFlow()` 时 `subAgentIds` 非空（否则 400）。

### 5.2 SSE 对话（`POST /api/v1/chat/stream`）

`produces = text/event-stream`，`SseEmitter` 超时 600s，事件循环见 `AgentChatService`。

请求体 `ChatStreamRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `agentId` | Long | 是 | 目标 Agent |
| `sessionId` | String | 否 | 空则 UUID 新建；用于 `RunnableConfig.threadId` 与消息归档 |
| `message` | String | 是 | 用户消息 |
| `attachments` | List<String> | 否 | 预留（v0.1 未处理） |

**事件序列（正常）**：

```
run.started → message.delta × N → message.completed → usage → run.completed
```

**事件序列（失败）**：`... → error{code,message} → run.completed{status:FAILED}`
（启动阶段失败也走 SSE `error`，不再返回 JSON 500）。

**心跳**：每 15s 发一次 `event: ping`，data 为 `{"content":"","type":"ping"}`；
心跳由共享 daemon 调度线程池驱动，emitter 完成/超时/出错时取消。

每类事件的 data 字段（`event/AgentEvents.java` 的 record，序列化时**额外注入 `type`=事件名**，便于前端统一解析）：

| 事件 | data 字段 |
| --- | --- |
| `run.started` | `runId, agentId, sessionId, model` |
| `message.delta` | `content` |
| `message.completed` | `content`（完整回答） |
| `tool.call` | `toolName, arguments, riskLevel` |
| `tool.result` | `toolName, resultSummary（≤500 字符）, success, elapsedMs` |
| `citation` | `index, documentId, filename, chunkIndex, score` |
| `usage` | `model, promptTokens, completionTokens, totalTokens, cost` |
| `interrupted` | `approvalId, toolName, arguments, reason`（HITL，v0.2 才发送） |
| `run.completed` | `runId, status, elapsedMs, usage` |
| `error` | `code（数字，如 50000）, message` |
| `ping` | `content:""` |

**真实 JSON 摘录（18084 e2e 实测，Mock 模型）**：

```
event:run.started
data:{"runId":1,"agentId":1,"sessionId":"b9f1f196-f3d4-41a1-8c9f-ce5de0dc9f61","model":"mock-chat","type":"run.started"}

event:message.delta
data:{"content":"（Moc","type":"message.delta"}

event:message.completed
data:{"content":"（Mock 模型）已收到你的消息：请用一句话介绍你自己。这是模拟的流式输出。","type":"message.completed"}

event:usage
data:{"model":"mock-chat","promptTokens":176,"completionTokens":11,"totalTokens":187,"cost":0.0,"type":"usage"}

event:run.completed
data:{"runId":1,"status":"SUCCEEDED","elapsedMs":212,"usage":{"model":"mock-chat","promptTokens":176,"completionTokens":11,"totalTokens":187,"cost":0.0},"type":"run.completed"}
```

实现要点（`AgentChatService.runStream`）：

```java
reactAgent.stream(execution.message(), execution.config())
        .takeWhile(node -> !context.isCancelled())     // cancel / sink 断开时停止
        .doOnNext(node -> handleNode(node, context, answer, fallback))
        .blockLast();                                   // 在 agentRunExecutor 线程阻塞消费
// 兜底：没有 AGENT_MODEL_STREAMING 分块时，用最后一个 AssistantMessage 文本补一条 delta
if (answer.length() == 0 && fallback.length() > 0) { emit MESSAGE_DELTA(fallback); }
context.resolveStreamOutput(finalAnswer);              // 回填流式 MODEL 步骤
context.emit(MESSAGE_COMPLETED, ...); context.emit(USAGE, context.usageSnapshot());
``` 

`handleNode` 只把 `StreamingOutput.getOutputType() == AGENT_MODEL_STREAMING` 的 chunk 作为 delta 推送，
同时累计 `node.tokenUsage()`；`AGENT_MODEL_FINISHED` 等事件不重复推送（避免正文重复）。
非 ReactAgent（flow 类型）走 `executionService.execute()`（阻塞）后**一次性**发一条 `message.delta`。

> 注意：`usage` 事件是 `RunContext` 的**跨调用累计值**（拦截器 + NodeOutput.tokenUsage 累加），
> 与 audit 表里"每次模型调用一条"的记录口径不同（实测 SSE run usage=176/11/187，audit 记录该 run 为 16/1/17）。
> 前端展示以 SSE `usage` 为准，账单/审计以 `af_token_usage` 为准。

### 5.3 运行记录、取消、审批（`RunController`，`/api/v1`）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/agents/{id}/run` | `agent:run` | 非流式运行，body `{"message":"...","sessionId":"可选"}`，返回完整 `RunResponse` |
| GET | `/api/v1/runs/{runId}` | `agent:read` | 运行详情 + steps |
| POST | `/api/v1/runs/{runId}/cancel` | `agent:run` | 置取消标记（本机执行生效），返回当前 run |
| POST | `/api/v1/runs/{runId}/approve` | `agent:run` | HITL：body `{"approved":true,"comment":"..."}`，只允许 RUNNING，写 APPROVAL 步骤 |

`RunResponse` 字段：`id, agentId, agentVersion, sessionId, status, input, output, promptTokens,
completionTokens, totalTokens, modelName, errorMessage, traceId, startedAt, finishedAt, elapsedMs, steps[]`；
`steps[]` 元素：`seqNo, agentName, nodeName, stepType, toolName, input, output, status, elapsedMs`。

真实响应摘录（`GET /api/v1/runs/1`，data 部分）：

```json
{"id":1,"agentId":1,"agentVersion":1,"sessionId":"b9f1f196-...","status":"SUCCEEDED",
 "input":"请用一句话介绍你自己",
 "output":"（Mock 模型）已收到你的消息：请用一句话介绍你自己。这是模拟的流式输出。",
 "promptTokens":176,"completionTokens":11,"totalTokens":187,"modelName":"mock-chat",
 "traceId":"0bded44664384e208c364c02dec89a21",
 "startedAt":"2026-10-29T20:56:33.581722","finishedAt":"2026-10-29T20:56:34.056493","elapsedMs":474,
 "steps":[{"seqNo":1,"stepType":"MODEL","input":"请用一句话介绍你自己",
           "output":"（Mock 模型）已收到你的消息：请用一句话介绍你自己。这是模拟的流式输出。",
           "status":"SUCCEEDED","elapsedMs":7}]}
```

SEQUENTIAL 实测（agentId=4，subAgentIds=[2,3]）`POST /api/v1/agents/4/run` → `status=SUCCEEDED`、
`steps=2`（两条 MODEL：第一条 input=用户问题，第二条 input=前一个子 Agent 的输出），
promptTokens=37 / completionTokens=48 / totalTokens=85。

### 5.4 工具与编排接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/tools` | `agent:read` | `ApiResponse<List<ToolInfo>>`，`ToolInfo(name, description, riskLevel, source="builtin")` |
| GET/POST | `/api/v1/orchestrations` | `agent:read`/`agent:write` | 列表（按 id 倒序，最多 200）/ 创建 |
| GET/PUT/DELETE | `/api/v1/orchestrations/{id}` | `agent:read`/`agent:write` | 详情/更新/删除 |

`OrchestrationRequest`：`{name, description, type, configJson, enabled}`，`type` 白名单
`SEQUENTIAL|PARALLEL|ROUTING|SUPERVISOR`，名称租户内唯一。
`GET /api/v1/orchestrations` 空库返回 `{"code":0,"message":"OK","data":[],...}`。

所有响应统一 `ApiResponse`（`code/message/data/traceId/timestamp`），SSE 之外的分页统一 `PageResult`。

---

## 6. Token 审计接入

### 6.1 使用位置

| 位置 | 代码 | 说明 |
| --- | --- | --- |
| 运行线程绑定 | `AgentExecutionService.bindRunThread` 调用 `UsageContext.open(tenantId, userId, agentId, runId, sessionId)` | 必须在**执行模型调用的同一线程**打开（ThreadLocal） |
| 运行线程结束 | `AgentExecutionService.complete` 的 `finally` 调用 `UsageContext.close()` | 与 open 配对；异常路径也执行 |
| 流式 | `AgentChatService.runStream` 经 `bindRunThread` 后消费 Flux，`complete` 收尾 | 模型调用发生在同一 `agentRunExecutor` 线程（`blockLast` 消费），上下文可见 |

`UsageContext.open(...)` 的 5 参数签名（audit 模块）把 `agentId/runId/sessionId` 与租户/用户一起放入
ThreadLocal，`UsageRecordingChatModel` 每次 `call/stream` 完成后读取并写 `TokenUsageEvent`：

```java
UsageContext.open(tenantId, userId, agentId, runId, sessionId);
try {
    // 模型调用链（ReactAgent / flow agent / 工具内二次模型调用）
} finally {
    UsageContext.close();
}
```

### 6.2 为什么能落库

`ModelProviderFactory`（audit）返回的 ChatModel 已被 `UsageRecordingChatModel` 包装，
所以 T4 **不需要自己写 usage 记录**；T4 只负责（a）打开归属上下文、（b）把累计 usage 写进
`agent_run` 与 SSE `usage` 事件。

### 6.3 实测证据（18084，`GET /api/v1/audit/usage/records`，total=3）

```json
{"id":1,"traceId":"...","tenantId":1,"userId":1,"agentId":1,"runId":1,
 "sessionId":"b9f1f196-...","modelId":1,"modelName":"mock-chat","provider":"MOCK",
 "promptTokens":16,"completionTokens":1,"totalTokens":17,"stream":true,"success":true,"latencyMs":21}
```

- SSE 对话产生的记录 `stream=true`（ChatModel.stream 路径）；
- SEQUENTIAL 运行产生两条 `stream=false`（两次 `call`），同属 `runId=2`；
- 记录里 `agentId/runId/sessionId/traceId` 与 `agent_run` 一致，说明 `UsageContext` 归属正确。

---

## 7. 扩展指南

### 7.1 新增一个工具（3 步）

1. 新建 `@Component` 实现 `AgentToolDefinition`，并在 `@Tool` 方法上声明参数描述：

```java
@Component
public class MyTool implements AgentToolDefinition {
    public String name() { return "my_tool"; }
    public String description() { return "做什么"; }
    public String riskLevel() { return "LOW"; }          // LOW/MEDIUM/HIGH
    public Object toolBean() { return this; }

    @Tool(name = "my_tool", description = "做什么")
    public String run(@ToolParam(description = "参数说明") String arg) { ... }
}
```

2. 外部依赖一律 `ObjectProvider<T>` 注入（模块未就绪时返回降级文本，不要让 Bean 缺失导致启动失败）；
3. 需要审计/事件时取 `RunContextHolder.current()`（可能为 null，非运行期调用要判空），
   用 `context.emit(AgentEvent.CITATION, ...)` 推送事件、`context.addStep/finishStep` 记录步骤。

工具签名硬约束（见 §4.4）：**不要**用 `long/int/double` 原始类型、`Optional`、集合/Map 入参，
不要返回 `CompletableFuture`/`Flux`；复杂参数用 `String` 传 JSON。

### 7.2 新增一种 Agent 类型

1. `domain/AgentType` 加枚举；
2. `OrchestrationService.TYPES` 与 `AgentService.validateRequest` 同步白名单/子 Agent 规则；
3. `ReactAgentFactory.build` 的 `switch` 加分支：SAA 有现成 flow agent 就用 builder（`subAgents` + `saver`），
   没有则考虑 `graph-core` 的 `StateGraph` 自行编排；
4. 若需与已有运行链路一致，保持 `Agent` 接口（`invoke/stream/call`）返回类型不变，
   `AgentExecutionService.extractText` 能处理输出即可；否则扩展 `extractText`；
5. 补单测：`ReactAgentFactoryTest` 断言返回类型，非法配置抛 `BizException`。

### 7.3 接入 HITL（人工审批）

目标：高风险工具（`sql_query`/`code_execute`）执行前暂停、发 `interrupted`、等审批后恢复。

当前 v0.1：`/runs/{runId}/approve` 只校验 RUNNING 并写 `APPROVAL` 步骤，**不真正恢复执行**。
v0.2 步骤（SAA 1.1.2.4 已具备能力，类已用 `javap` 确认存在）：

1. **必须配置 saver**（`ReactAgent.builder().saver(...)`，当前是 `MemorySaver`；重启后中断态丢失，
   生产应换 `RedisSaver`）；
2. 注册 SAA 自带的 HITL hook（`spring-ai-alibaba-agent-framework` 的
   `com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook`，继承 `ModelHook` 并实现
   `InterruptableAction`），按工具声明审批点：

```java
HumanInTheLoopHook hitl = HumanInTheLoopHook.builder()
        .approvalOn("sql_query", "执行只读 SQL 前需要人工确认")   // 或 approvalOn(Map<String, ToolConfig>)
        .build();
builder.hooks(List.of(ModelCallLimitHook.builder().runLimit(maxIterations).build(), hitl));
```

3. 运行到审批点时由 `InterruptableAction.interrupt(...)` 产生中断态（`InterruptionMetadata`），
   写入 saver 的 checkpoint；Agent 侧调用 `ReactAgent.interrupt(config)` 挂起，发 `interrupted` 事件，
   `approvalId` 持久化到 `agent_run`/前端；
4. 审批接口收到 `approved=true` 后，用**同一 `threadId`（本模块 = `sessionId`）**的 `RunnableConfig`
   从 checkpoint 恢复执行（SAA resume API），继续跑工具并推送后续事件；
5. 前端按 `interrupted` → 弹窗 → `POST /runs/{id}/approve` → 继续接收事件。

> 要点：hook 没有 saver 无法中断/恢复（`MemorySaver` 可用但进程重启即丢，生产必须 `RedisSaver`）；
> `threadId` 必须与首次运行一致，否则找不到 checkpoint。

### 7.4 接入真实 RedisSaver

无 Redis 时使用 `MemorySaver`（每次构建新实例，进程内、不持久）。生产接入步骤：

```java
// 依赖：agentforge.memory.redis-enabled=true（app 配置）且 RedissonClient Bean 存在
RedissonClient redisson = ...;                                  // app 装配（Redisson 自动配置或手工 Bean）
StateSerializer serializer = new SpringAIJacksonStateSerializer(...); // graph-core 提供：
//   com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer
//   （同包还有 JacksonStateSerializer / PlainTextStateSerializer；构造参数以所用 SAA 版本为准）
RedisSaver saver = RedisSaver.builder()
        .redisson(redisson)
        .stateSerializer(serializer)
        .ttl(24, TimeUnit.HOURS)                                // 可选：checkpoint 过期时间
        .build();
// ReactAgentFactory：把 saver 字段由 new MemorySaver() 改为注入的 BaseCheckpointSaver
```

`RedisSaver.Builder` 已实测签名：`redisson(RedissonClient)`、`stateSerializer(StateSerializer)`、
`ttl(long, TimeUnit)`、`build()`。
要求：`RedisSaver` 首次使用需初始化索引（SAA 的 `createIndexes` 或手动建 `checkpoint:*` 键空间），
升级前验证 `threadId` 一致性与 TTL，避免 checkpoint 无限增长。

### 7.5 已知限制（v0.1）

| 限制 | 影响 | 计划 |
| --- | --- | --- |
| 无 `SupervisorAgent`，SUPERVISOR= LlmRoutingAgent 近似 | 仅单次分派，无监督循环/任务拆解 | v0.2 用 StateGraph 循环编排 |
| 每次运行 `new MemorySaver()`，不跨运行/重启保存 | 多轮对话不自动回放历史消息（`agent_message` 已存但未注入 prompt）；中断/恢复不可用 | 接入 RedisSaver + 会话历史装配 |
| 多轮上下文未装配 | 同一 `sessionId` 的后续问题不带前文，模型只看当前 `message` | v0.2 读取最近 N 条 `agent_message` 作为历史 |
| `PARALLEL` 子 Agent 共享 `RunContext` 写 steps | 并行时 `seq_no` 由 `AtomicInteger` 保证唯一，但顺序非确定 | 可接受；如需确定顺序按 agent 分组 |
| 编排表与运行入口未打通 | `/orchestrations` 只存配置，不能直接运行 | v0.2 支持 `orchestrationId` 运行 |
| 高风险工具无强制审批 | 工具会直接执行，仅事件标注 riskLevel | v0.2 HITL（§7.3） |
| 取消仅本机生效、RUNNING 记录无自动回收 | 多实例/崩溃场景 run 会停留 RUNNING | 增加心跳/超时扫描任务 |
| audit 记录与 SSE usage 口径不同 | 前端展示累计值，账单按调用条数 | 已记录，文档说明 |

---

## 8. 验证与测试

### 8.1 单元测试（`agentforge-agent/src/test/java`，19 个全绿）

| 测试类 | 覆盖点 |
| --- | --- |
| `tool/CalculatorToolTest`（4） | `(12+30)*2=84`、`10/4=2.5`、`-3+5*2=7`、非法表达式/除零返回"计算失败" |
| `tool/AgentToolRegistryTest`（3） | 顺序去重、未注册工具抛 `BizException`、riskLevel/list |
| `runtime/ReactAgentFactoryTest`（6） | SINGLE→`ReactAgent`、SEQUENTIAL→`SequentialAgent`、PARALLEL、SUPERVISOR→`LlmRoutingAgent`、单子 Agent 的 PARALLEL 抛 40000、无子 Agent 的 SUPERVISOR 抛 40000 |
| `controller/ChatControllerTest`（2） | emitter 透传、`BizException` 透传 |
| `service/AgentChatServiceJsonTest`（4） | 事件 JSON 含 `type` 与契约字段（message.delta/run.started/usage/error） |

命令：`mvn -B -pl agentforge-agent test`（PowerShell 下如需跳过测试编译写 `"-Dmaven.test.skip=true"`，**必须加引号**）。

### 8.2 端到端验证（18084，严格 `ddl-auto=validate`）

```bash
java -jar agentforge-app/target/agentforge-app-0.1.0-SNAPSHOT.jar \
  --server.port=18084 --spring.profiles.active=dev \
  "--spring.datasource.url=jdbc:h2:file:./.data/agentforge-t4e2e;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
```

脚本 `.data/e2e-t4.ps1`（10 步）实测结果：

| 步骤 | 结果 |
| --- | --- |
| 健康检查 / 登录 | 200 / 200（dev 种子管理员 `admin@agentforge.dev`） |
| 建 SINGLE agent | 200，agentId=1，version=1 |
| `POST /chat/stream` | SSE 正常：`run.started×1, message.delta×10, message.completed×1, usage×1, run.completed×1` |
| `GET /runs/1` | `SUCCEEDED`、steps[0] 为 MODEL 真实文本、elapsedMs=474 |
| `GET /tools` | 200，7 个工具与风险等级符合预期 |
| 2 子 agent + SEQUENTIAL 父 agent | 创建成功；`POST /agents/4/run` → `SUCCEEDED`、steps=2 |
| `GET /orchestrations` | 200（空列表） |
| `GET /audit/usage/records` | total=3，SSE run `stream=true`，SEQUENTIAL 两条 `stream=false` |
| 失败计数 | 0 |

---

## 9. 关键文件索引

| 关注点 | 文件 |
| --- | --- |
| Agent 构建 | `agentforge-agent/src/main/java/com/agentforge/agent/runtime/ReactAgentFactory.java` |
| 模型包装（ChatOptions 兼容） | `.../runtime/JacksonSafeChatModel.java` |
| 运行上下文 / 事件 / 注册表 | `.../runtime/{RunContext,RunContextHolder,RunRegistry,AgentEventSink}.java` |
| 拦截器 | `.../runtime/{RunModelInterceptor,RunToolInterceptor}.java` |
| 运行生命周期 | `.../service/AgentExecutionService.java` |
| SSE 对话 | `.../service/AgentChatService.java` |
| 非流式运行 | `.../service/AgentRunService.java` |
| CRUD/版本 | `.../service/AgentService.java` |
| 工具与注册表 | `.../tool/*.java` |
| 控制器 | `.../controller/*.java` |
| 迁移 | `agentforge-app/src/main/resources/db/migration/V6__agent.sql` |
| 契约 | `docs/04-API契约.md` §2.6 / §3 / §5 |
