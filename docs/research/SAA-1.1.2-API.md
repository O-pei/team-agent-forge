# Spring AI Alibaba 1.1.2.x 企业级多智能体平台 · 官方 API 调研

> 基线：Spring AI Alibaba **1.1.2.0**／Spring AI **1.1.2**／Spring AI Extensions **1.1.2.1**／Spring Boot **3.5.x**／JDK 21
> 所有结论来自官方文档、官方 BOM POM 与官方仓库源码；标注「未确认」的条目官网未写清。

---

## 1. Maven 坐标与版本矩阵

**结论**：必须同时导入 3 个 BOM。`spring-ai-alibaba-bom:1.1.2.0` 只管理 7 个官方 artifact，**dashscope starter 不在主 BOM**，在 extensions-bom 中且版本是 1.1.2.1（两套版本号并存是官方现状，不是笔误）。

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.alibaba.cloud.ai</groupId>
      <artifactId>spring-ai-alibaba-bom</artifactId>
      <version>1.1.2.0</version><type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>com.alibaba.cloud.ai</groupId>
      <artifactId>spring-ai-alibaba-extensions-bom</artifactId>
      <version>1.1.2.1</version><type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>1.1.2</version><type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- Agent Framework（ReactAgent / FlowAgent / Hooks） -->
  <dependency><groupId>com.alibaba.cloud.ai</groupId><artifactId>spring-ai-alibaba-agent-framework</artifactId></dependency>
  <!-- Graph Core（StateGraph / checkpointer） -->
  <dependency><groupId>com.alibaba.cloud.ai</groupId><artifactId>spring-ai-alibaba-graph-core</artifactId></dependency>
  <!-- 模型：DashScope 必须显式写 1.1.2.1（来自 extensions-bom） -->
  <dependency><groupId>com.alibaba.cloud.ai</groupId><artifactId>spring-ai-alibaba-starter-dashscope</artifactId><version>1.1.2.1</version></dependency>
  <!-- 沙箱 -->
  <dependency><groupId>com.alibaba.cloud.ai</groupId><artifactId>spring-ai-alibaba-sandbox</artifactId></dependency>
  <!-- Chat UI（可选，内嵌） -->
  <dependency><groupId>com.alibaba.cloud.ai</groupId><artifactId>spring-ai-alibaba-studio</artifactId></dependency>
  <!-- MCP client（Spring AI 侧） -->
  <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-mcp-client</artifactId></dependency>
</dependencies>
```

主 BOM（`spring-ai-alibaba-bom:1.1.2.0`）实测仅含以下 7 项，均为 1.1.2.0：
`spring-ai-alibaba-starter-a2a-nacos`、`-starter-config-nacos`、`-starter-graph-observation`、`-agent-framework`、`-graph-core`、`-studio`、`-sandbox`。

extensions-bom（1.1.2.1，groupId 同为 `com.alibaba.cloud.ai`）含：`spring-ai-alibaba-dashscope`、`-starter-dashscope`、`-autoconfigure-dashscope`、`-starter-memory`、`-starter-memory-long`、`-starter-memory-jdbc/-redis/-memcached/-mongodb/-tablestore/-elasticsearch/-mem0`、`-starter-mcp-registry`、`-starter-mcp-router`、`-starter-mcp-distributed`、`-starter-mcp-gateway`、`-mcp-common/-registry/-router/-distributed/-gateway`、`-starter-rag`、`-starter-nacos-prompt`、`-starter-arms-observation`、大量 `-starter-tool-calling-*` / `-starter-document-reader-*` / `-starter-document-parser-*` / `-store-*`。

Spring AI 侧（`spring-ai-bom:1.1.2`）关键坐标：`spring-ai-client-chat`、`spring-ai-model`、`spring-ai-mcp`、`spring-ai-mcp-annotations`、`spring-ai-starter-mcp-client`/`-webflux`、`spring-ai-starter-mcp-server`/`-webmvc`/`-webflux`/`-common`、`spring-ai-starter-model-chat-memory`、`-chat-memory-repository-{jdbc,mongodb,neo4j,cassandra,cosmos-db}`、`spring-ai-starter-model-{openai,dashscope 侧的 deepseek 等}`。

来源：
- 版本矩阵 https://java2ai.com/docs/versions （SAA 1.1.2.0 ↔ Spring AI 1.1.2 ↔ Extensions 1.1.2.1/1.1.2.0 ↔ Boot 3.5.x）
- 安装说明 https://java2ai.com/docs/overview
- BOM 内容 https://repo1.maven.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-bom/1.1.2.0/spring-ai-alibaba-bom-1.1.2.0.pom
- Extensions BOM https://repo1.maven.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-extensions-bom/1.1.2.1/spring-ai-alibaba-extensions-bom-1.1.2.1.pom
- Spring AI BOM https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/1.1.2/spring-ai-bom-1.1.2.pom

---

## 2. ReactAgent 构造 / Builder API

**结论**：`ReactAgent` 由 `Builder` 创建，本质是预置好的 Reason-Act 图；`call()` 同步、`stream()` 返回 `Flux<NodeOutput>`、`invoke()` 亦可（sandbox README 用 `agent.invoke(...)`）。工具支持三种注册方式：`@Tool` 方法对象（`methodTools`）、`FunctionToolCallback`（`tools`）、`ToolCallbackProvider`（`toolCallbackProviders`，MCP 场景）。`toolNames` 必须搭配 `resolver`，否则运行时抛异常。

```java
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;

public class CalculatorTools {
    @Tool(description = "计算两个整数之和")
    public int add(@ToolParam(description = "第一个加数") int a,
                   @ToolParam(description = "第二个加数") int b) { return a + b; }

    @Tool(description = "查询天气；city 必填，unit 可选")
    public String weather(@ToolParam(description = "城市") String city,
                          @ToolParam(description = "单位", required = false) String unit) {
        return city + " 晴 25" + (unit == null ? "C" : unit);
    }
}

// 需要在工具里读会话元数据时：
@Tool(description = "读取当前用户 ID")
public String whoAmI(ToolContext toolContext) {
    RunnableConfig cfg = (RunnableConfig) toolContext.getContext().get("config");
    return String.valueOf(cfg.metadata("user_id").orElse("anonymous"));
}

ChatModel chatModel = DashScopeChatModel.builder()
        .dashScopeApi(DashScopeApi.builder().apiKey(System.getenv("AI_DASHSCOPE_API_KEY")).build())
        .defaultOptions(DashScopeChatOptions.builder()
                .withModel("qwen-plus").withTemperature(0.7).withMaxToken(4096).build())
        .build();

ReactAgent agent = ReactAgent.builder()
        .name("enterprise_assistant")
        .description("企业知识助手")
        .model(chatModel)
        .systemPrompt("你是企业助手，回答必须标注信息来源。")   // 或 .instruction("...")
        .methodTools(new CalculatorTools())                    // @Tool 方法对象
        .tools(FunctionToolCallback.builder("now", (String zone) -> "2026-10-29")
                .description("获取当前日期").inputType(String.class).build())
        .saver(new MemorySaver())                              // 会话持久化/多轮
        .outputType(ContactInfo.class)                         // 结构化输出（可选）
        .returnReasoningContents(false)
        .build();

AssistantMessage answer = agent.call("帮我算 12 + 30");      // 同步
AssistantMessage answer2 = agent.call("继续", RunnableConfig.builder()
        .threadId("session-1").addMetadata("user_id", "user_123").build());

Flux<NodeOutput> flux = agent.stream("把上面结论写成报告", RunnableConfig.builder().threadId("session-1").build());
flux.doOnNext(out -> {
    if ("_AGENT_MODEL_".equals(out.node())) {
        System.out.println(((StreamingOutput<?>) out).message());
    } else if ("_AGENT_TOOL_".equals(out.node())) {
        Object msg = ((StreamingOutput<?>) out).message();
        if (msg instanceof ToolResponseMessage trm) System.out.println("tool: " + trm.getResponses());
    }
}).blockLast();
```

Builder 方法清单（官方文档出现过的全部）：`.name` `.model(ChatModel)` `.description` `.instruction` `.systemPrompt` `.tools(ToolCallback...)` `.tools(List<ToolCallback>)` `.methodTools(Object...)` `.toolCallbackProviders(ToolCallbackProvider)` `.toolNames(String...)`+`.resolver(ToolCallbackResolver)`（**必须成对**）`.hooks(...)` `.interceptors(...)` `.saver(CheckpointSaver)` `.outputSchema(String)` `.outputType(Class<?>)` `.outputKey(String)` `.returnReasoningContents(boolean)` `.includeContents(boolean)` `.subAgents(List)`（Flow 类 agent 用）。

工具签名限制（官方明确）：`Function` 形式不支持原始类型 / `Optional` / 集合 / 异步 / 响应式入参；`@Tool` 方法形式不支持 `Optional` / `CompletableFuture` / `Flux` / `Mono` / `Function` 参数。

结构化输出：`.outputType(X.class)`（推荐）或 `.outputSchema(new BeanOutputConverter<>(X.class).getFormat())`；结果既在 `AssistantMessage.getText()`（JSON 文本），也在 `OverAllState` 的 `structured_output` 键。DashScope/OpenAI 原生结构化输出会自动带上 `DashScopeResponseFormat.Type.JSON_OBJECT`，其他模型降级为 ToolCall 策略。

来源：
- https://raw.githubusercontent.com/spring-ai-alibaba/website/main/docs/frameworks/agent-framework/tutorials/agents.md
- https://raw.githubusercontent.com/spring-ai-alibaba/website/main/docs/frameworks/agent-framework/tutorials/tools.md
- https://raw.githubusercontent.com/spring-ai-alibaba/website/main/docs/frameworks/agent-framework/tutorials/structured-output.md
- 页面版 https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents

---

## 3. 多智能体编排（Sequential / Parallel / Routing / Supervisor）

**结论**：全部在 `com.alibaba.cloud.ai.graph.agent.flow.agent.*` 包，均为 `Builder` 模式、`subAgents(List)` 组合，统一返回 `Optional<OverAllState>`。`Supervisor` 是多步循环（可返回 `FINISH`），`LlmRouting` 是单次路由。自定义编排继承 `FlowAgent`。

```java
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.Optional;

// 1) 顺序：A → B → C
SequentialAgent pipeline = SequentialAgent.builder()
        .name("research_pipeline").description("检索→分析→写作")
        .subAgents(List.of(searchAgent, analysisAgent, writerAgent))
        .build();
Optional<OverAllState> r1 = pipeline.invoke("调研 2026 年国产大模型进展");

// 2) 并行：同时跑，合并结果
ParallelAgent fanout = ParallelAgent.builder()
        .name("multi_source").subAgents(List.of(webAgent, kbAgent, dbAgent))
        .mergeOutputKey("merged_results")
        .mergeStrategy(new ParallelAgent.DefaultMergeStrategy())
        .build();
// 自定义合并：implements ParallelAgent.MergeStrategy
//   Map<String,Object> merge(Map<String,Object> mergedState, OverAllState state)

// 3) LLM 路由：单次判定交给哪个子 agent
LlmRoutingAgent router = LlmRoutingAgent.builder()
        .name("router").model(chatModel)
        .subAgents(List.of(hrAgent, financeAgent, itAgent))
        .instruction("根据用户问题选择最合适的处理 agent")   // 追加为 UserMessage
        .build();

// 4) Supervisor：多步循环，子 agent 完成后回到 supervisor，可 FINISH
SupervisorAgent supervisor = SupervisorAgent.builder()
        .name("supervisor").model(chatModel)
        .subAgents(List.of(hrAgent, financeAgent, itAgent))
        .build();
```

- instruction 占位符：`{input}`、`{outputKey}`、`{stateKey}`（从 `OverAllState` 取值，取不到保留原文）。
- 上下文控制：`outputKey`、`returnReasoningContents`、`includeContents`；读上游产物 `state.value("article")` → `Optional<Object>`。
- `LlmRoutingAgent` 的 `.systemPrompt()` 覆盖默认 SystemMessage，`.instruction()` 追加 UserMessage；`SupervisorAgent` 内置**最多 2 次重试**。
- 自定义：继承 `FlowAgent`，实现 `protected abstract StateGraph buildSpecificGraph(FlowGraphBuilder.FlowGraphConfig config) throws GraphStateException`，再提供 `FlowAgentBuilder<A,B>` 子类。

来源：https://raw.githubusercontent.com/spring-ai-alibaba/website/main/docs/frameworks/agent-framework/advanced/multi-agent.md

---

## 4. Hooks / Interceptor（含 token usage）

**结论**：四类扩展点——`AgentHook`（agent 前后）、`ModelHook`（模型前后）、`MessagesModelHook`（直接改消息列表，最常用）、`ModelInterceptor` / `ToolInterceptor`（可拦截并改写请求/响应）。**能拿到 token usage**：`ModelInterceptor` 的 `ModelResponse` 持有一个 `ChatResponse`，从中可读 `ChatResponseMetadata.getUsage()`（源码已确认，见下）。

```java
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;

// ① 统计 token（模型调用后）
public class TokenUsageInterceptor implements ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        ModelResponse response = handler.call(request);
        if (response.getChatResponse() != null
                && response.getChatResponse().getMetadata() != null) {
            Usage usage = response.getChatResponse().getMetadata().getUsage();
            System.out.printf("prompt=%s completion=%s total=%s%n",
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        } // 流式场景 ModelResponse 里装的是 Flux<ChatResponse>，需自行累加
        return response;
    }
}

// ② 改写系统提示 / 动态注入工具
public class DynamicPromptInterceptor implements ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        ModelRequest mutable = ModelRequest.builder(request)
                .systemMessage(new SystemMessage("当前租户：ACME"))
                .dynamicToolCallbacks(List.of(extraTool))
                .tools(List.of("calculator"))
                .build();
        return handler.call(mutable);
    }
}

// ③ 工具调用前后
public class AuditToolInterceptor implements ToolInterceptor {
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        long t = System.currentTimeMillis();
        ToolCallResponse resp = handler.call(request);
        System.out.printf("tool=%s cost=%dms%n", request.getToolName(), System.currentTimeMillis() - t);
        return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), resp.getResult());
    }
}

// ④ 消息列表级 hook（裁剪/替换/跳转）
@HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
public class TrimHook extends MessagesModelHook {
    @Override public String getName() { return "trim"; }
    @Override public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        return new AgentCommand(messages.subList(Math.max(0, messages.size() - 20), messages.size()),
                                UpdatePolicy.REPLACE);
    }
    // 跳转到 END: new AgentCommand(JumpTo.end, messages) + @Override canJumpTo() { return List.of(JumpTo.end); }
}

ReactAgent agent = ReactAgent.builder().name("a").model(chatModel)
        .interceptors(List.of(new TokenUsageInterceptor(), new AuditToolInterceptor()))
        .hooks(List.of(new TrimHook()))
        .build();
```

- 执行顺序：before 类正序、after 类逆序、interceptor 嵌套包裹；`config.context()` 是整条链共享的 `Map`（做计数/限流/上下文透传）。
- `AgentHook`/`ModelHook` 签名：`CompletableFuture<Map<String,Object>> beforeAgent/afterAgent/beforeModel/afterModel(OverAllState, RunnableConfig)`。
- 状态操作辅助类：`com.alibaba.cloud.ai.graph.state.RemoveByHash`、`ReplaceAllWith`（消息去重/整体替换）。
- 内置实现（可直接用）：
  - `SummarizationHook`（model / maxTokensBeforeSummary / messagesToKeep）
  - `HumanInTheLoopHook`（`.approvalOn("toolName", ToolConfig.builder().description("...").build())`，**必须配 `.saver(...)`**，否则无法暂停恢复）
  - `ModelCallLimitHook`（`runLimit(5)`）→ 防死循环
  - `PIIDetectionHook`（`piiType(PIIType.EMAIL)` / `strategy(RedactionStrategy.REDACT)` / `applyToInput(true)`）
  - `ToolRetryInterceptor`（maxRetries / onFailure(RETURN_MESSAGE)）
  - `TodoListInterceptor`、`ToolSelectionInterceptor`、`ToolEmulatorInterceptor(model)`、`ContextEditingInterceptor(trigger/clearAtLeast)`
  - `SkillsAgentHook`、`ShellToolAgentHook`（见 §Skills）

来源：
- hooks 教程 https://raw.githubusercontent.com/spring-ai-alibaba/website/main/docs/frameworks/agent-framework/tutorials/hooks.md
- `ModelResponse` 源码（含 `getChatResponse()`）https://raw.githubusercontent.com/alibaba/spring-ai-alibaba/main/spring-ai-alibaba-agent-framework/src/main/java/com/alibaba/cloud/ai/graph/agent/interceptor/ModelResponse.java

---

## 5. Memory

**结论**：Agent Framework 的「记忆」= Graph 状态（`messages` 键）+ Checkpointer，通过 `.saver(...)` 挂载；跨轮会话用 `threadId` 区分线程。生产 Redis 持久化用 `RedisSaver(RedissonClient)`（Redisson，不是 Spring Data Redis）。

```java
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.RedisSaver;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

RedissonClient redisson = Redisson.create(
        new Config().useSingleServer().setAddress("redis://127.0.0.1:6379").setDatabase(0).upstream());

ReactAgent agent = ReactAgent.builder().name("mem-agent").model(chatModel)
        .saver(new RedisSaver(redisson))              // 生产；单机调试用 new MemorySaver()
        .build();

RunnableConfig cfg = RunnableConfig.builder().threadId("tenant-42:user-7").build();
agent.call("我叫张三", cfg);
agent.call("我叫什么？", cfg);                          // 同 threadId → 自动带历史
```

- 配置了 saver 后 **`threadId` 必填**，否则无法持久化/恢复。
- Spring AI 侧记忆：`spring-ai-starter-model-chat-memory` + `-chat-memory-repository-{jdbc,mongodb,neo4j,cassandra,cosmos-db}`（BOM 1.1.2 实测清单里**没有 redis** repository），走 `ChatMemory` + `MessageChatMemoryAdvisor` 的 `ChatClient` 路线。
- **未确认**：Agent Framework 的 saver-memory 与 Spring AI `ChatMemory` 的官方关系/桥接（文档未提），二者目前是两套并行机制；若要在 ReactAgent 里复用 `ChatMemory`，需自定义 `MessagesModelHook` 手工注入历史——官网无示例。

来源：https://raw.githubusercontent.com/spring-ai-alibaba/website/main/docs/frameworks/agent-framework/tutorials/memory.md 、https://java2ai.com/docs/frameworks/graph-core/core/persistence

---

## 6. Sandbox（spring-ai-alibaba-sandbox）

**结论**：把 **AgentScope Runtime** 的沙箱能力包成 Spring AI `ToolCallback`，让 agent 在**隔离 Docker 容器**内执行 Python/shell、文件系统操作、浏览器自动化。**依赖 Docker daemon**，不是本地裸进程，也不是通用 K8s 远程沙箱客户端。

```java
import com.alibaba.cloud.ai.sandbox.ToolkitInit;
import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.manager.ManagerConfig;
import io.agentscope.runtime.sandbox.manager.SandboxService;

SandboxService sandboxService = new SandboxService(ManagerConfig.builder().build());
sandboxService.start();
BaseSandbox sandbox = new BaseSandbox(sandboxService, "user-123", "session-456");

ReactAgent agent = ReactAgent.builder().name("sandbox_agent").model(chatModel)
        .instruction("需要执行代码时调用 Python 或 shell 工具。")
        .tools(List.of(ToolkitInit.RunPythonCodeTool(sandbox),
                       ToolkitInit.RunShellCommandTool(sandbox)))
        .build();
try { agent.invoke("用 Python 计算斐波那契前 20 项"); }
finally { sandboxService.cleanupAllSandboxes(); }

// Spring Boot 托管：
@Bean(initMethod = "start", destroyMethod = "cleanupAllSandboxes")
SandboxService sandboxService() { return new SandboxService(ManagerConfig.builder().build()); }
```

- `ToolkitInit` 还提供 `FilesystemSandbox`（读写/编辑/移动/搜索文件）、`BrowserSandbox`（导航/交互/截图/页面检查）。
- 隔离粒度：每个 (user, session) 一个 `BaseSandbox`；不互信负载禁止复用 sandbox id。
- 环境要求：Java 17+、可达的 Docker、一个 chat model。
- 生产要点（官方 README 明示）：容器资源限额、网络策略、镜像白名单、只读挂载、清理与使用量监控；接远程沙箱控制面需自写 ToolCallback。
- **未确认**：无官方 Kubernetes/远程沙箱接入文档。

来源：https://raw.githubusercontent.com/alibaba/spring-ai-alibaba/main/spring-ai-alibaba-sandbox/README.md

---

## 7. MCP

**结论**：MCP client/server 能力全部来自 Spring AI 1.1.2 的 starter（坐标已由 BOM 证实）。ReactAgent 通过注入 `ToolCallbackProvider` 接入远端工具。SAA 自研的 MCP 网关/注册中心 starter 存在于 extensions-bom，但**官网无 API 文档（未确认）**。

```xml
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-mcp-client</artifactId></dependency>
<!-- WebFlux 场景 -->
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-mcp-client-webflux</artifactId></dependency>
```
```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        name: enterprise-mcp-client
        version: 1.0.0
        type: ASYNC                 # SYNC | ASYNC
        toolcallback:
          enabled: true
        streamable-http:            # 推荐；SSE 在新版本已 deprecated
          connections:
            weather:
              url: http://localhost:8080
              endpoint: /mcp
        sse:
          connections:
            legacy:
              url: http://localhost:8081
              sse-endpoint: /sse
```
```java
import org.springframework.ai.tool.ToolCallbackProvider;

@Bean
ReactAgent mcpAgent(ChatModel chatModel, ToolCallbackProvider provider) {
    return ReactAgent.builder().name("mcp_agent").model(chatModel)
            .toolCallbackProviders(provider)         // 或 .tools(provider.getToolCallbacks())
            .build();
}
```

Server 侧：`spring-ai-starter-mcp-server`（STDIO）/ `-webmvc`（Spring MVC，`POST /mcp`）/ `-webflux`（Reactor）。
```yaml
spring.ai.mcp.server.stdio: true
spring.ai.mcp.server.protocol: STREAMABLE      # STREAMABLE | SSE | STATELESS
spring.ai.mcp.server.type: SYNC                # SYNC 只注册同步注解方法，ASYNC 只注册异步
spring.ai.mcp.server.annotation-scanner.enabled: true
```
注解：`@McpTool`、`@McpResource`、`@McpPrompt`、`@McpComplete`；特殊参数 `McpMeta`、`@McpProgressToken`、`McpSyncServerExchange`/`McpAsyncServerExchange`、`McpTransportContext`、`CallToolRequest`（`McpTransportContext` 默认 `EMPTY`，需在 transport provider 配 `TransportContextExtractor`）。
安全：HTTP transport **默认无鉴权**，暴露到 localhost 之外必须自行用 Spring Security 包一层。

SAA 自有 MCP（仅坐标确认，API 未确认）：`spring-ai-alibaba-starter-mcp-gateway`、`-mcp-router`、`-mcp-registry`、`-mcp-distributed`（含 `spring-ai-alibaba-mcp-common`）。

来源：https://raw.githubusercontent.com/spring-ai-alibaba/website/main/docs/frameworks/agent-framework/tutorials/tools.md （client 用法）、https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html （server 配置，注意该站当前默认展示 2.0.x，1.1.x 选版本下拉）、BOM 坐标见 §1 源。

---

## 8. Graph Core（StateGraph / checkpointer / 流式）

**结论**：`StateGraph` 以 `KeyStrategyFactory` 定义状态合并语义（Replace/Append），`compile(CompileConfig)` 得到 `CompiledGraph`，提供 `invoke`/`stream`/`getState`/`getStateHistory`/`updateState`。Agent Framework 的 ReactAgent/FlowAgent 都是它的封装——**平台级复杂流程直接写 Graph 更可控**。

```java
import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

KeyStrategyFactory keyStrategyFactory = () -> {
    Map<String, KeyStrategy> m = new HashMap<>();
    m.put("messages", new AppendStrategy());
    m.put("next_node", new ReplaceStrategy());
    return m;
};

class ReadEmailNode implements NodeAction {
    @Override public Map<String, Object> apply(OverAllState state) throws Exception {
        return Map.of("messages", "email body", "next_node", "classify");
    }
}

StateGraph g = new StateGraph(keyStrategyFactory)
        .addNode("read_email", node_async(new ReadEmailNode()))
        .addNode("classify", node_async(state -> Map.of("next_node", "draft")))
        .addEdge(START, "read_email")
        .addEdge("read_email", "classify")
        .addConditionalEdges("classify",
                edge_async(s -> (String) s.value("next_node").orElse("draft")),
                Map.of("draft", "draft"))
        .addEdge("draft", END);

CompiledGraph app = g.compile(CompileConfig.builder()
        .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
        .interruptBefore("draft")            // 图级 HITL：在此节点前暂停
        .build());

RunnableConfig cfg = RunnableConfig.builder().threadId("t-1").build();
Flux<NodeOutput> stream = app.stream(Map.of(), cfg);   // 流式
Optional<NodeOutput> one = app.invoke(Map.of(), cfg);   // 一次性

StateSnapshot snap = app.getState(cfg);
System.out.println(snap.values() + " next=" + snap.next());
app.getStateHistory(cfg).forEach(s -> System.out.println(s.config().checkPointId()));

app.updateState(cfg, Map.of("approved", true), null);   // 恢复前写入状态（仍过 KeyStrategy reducer）
app.stream(null, cfg).blockLast();                      // 从断点继续
```

- `state.value("k")` → `Optional<Object>`；`state.data()` → `Map`。
- 设计原则：state 只放原始数据不放格式化 prompt；节点边界=checkpoint 边界；checkpoint 默认异步落盘不阻塞执行。
- Checkpointer 实现：`MemorySaver`、`PostgreSqlSaver`、`RedisSaver`、`MongodbSaver`；可用 `SaverConfig.builder().register(SaverConstant.MEMORY, new MemorySaver())`。
- 时光旅行：`RunnableConfig.builder().threadId("1").checkPointId("<uuid>").build()` 重放。

来源：https://raw.githubusercontent.com/spring-ai-alibaba/website/main/docs/frameworks/graph-core/quick-start.md 、https://raw.githubusercontent.com/spring-ai-alibaba/website/main/docs/frameworks/graph-core/core/persistence.md

---

## 9. 已知坑 / 版本冲突 / 额外仓库

1. **两套 BOM、两套版本号**：`agent-framework`/`graph-core`/`sandbox`/`studio` = 1.1.2.0，`starter-dashscope` 及其余 extensions 组件 = 1.1.2.1。少导 extensions-bom 会导致 dashscope 无法解析或版本漂移。
2. **`toolNames(...)` 必须配 `.resolver(ToolCallbackResolver)`**，否则运行时抛异常（文档明确）。
3. **`HumanInTheLoopHook` 必须配 `.saver(...)`**，否则无 checkpoint 可恢复，审批后无法续跑。
4. **`threadId` 是记忆/持久化的唯一开关**：配了 saver 却不传 threadId → 无法恢复、多轮失效。
5. **不要混淆 Graph 级 `interruptBefore` 与 Agent 级 `HumanInTheLoopHook`**：前者在 `CompileConfig` 上按节点名暂停（需 `updateState(..., null)` + 重新 `stream`），后者在工具调用维度按 `approvalOn` 暂停。
6. **工具签名限制**：Function 形式不支持原始类型/Optional/集合/异步/响应式入参；`@Tool` 方法形式不支持 `Optional`/`CompletableFuture`/`Flux`/`Mono`/`Function`。用包装对象（record/DTO）绕开。
7. **流式取值方式不同**：`stream()` 返回 `Flux<NodeOutput>`，模型增量在 `StreamingOutput.message()`，工具结果需 cast 为 `ToolResponseMessage`；`node()` 为 `"_AGENT_MODEL_"` / `"_AGENT_TOOL_"`。token usage 在流式下 `ModelResponse.getChatResponse()` 为 null，需自行累加。
8. **仓库/依赖仓库**：Spring AI 1.1.2 与 SAA 1.1.2.0 均在 Maven Central（repo1.maven.org 实测可拉取），**不需要** Spring Milestones/Snapshot 仓库；只有用 `-SNAPSHOT` 或 `spring-ai-alibaba-admin` 时才需额外配置——**未确认**（官网 quick-start 未提 snapshot 仓库）。
9. **MCP 鉴权默认关闭**：`spring-ai-mcp` HTTP transport 无内置鉴权，企业内网也必须自加 Security filter。
10. **Sandbox 强依赖 Docker**：CI/容器内运行需 Docker-in-Docker 或挂载宿主机 socket，否则 `SandboxService.start()` 失败；沙箱容器务必设资源上限与镜像白名单。
11. **SSE 传输已过时**：Spring AI 2.0 起 SSE deprecated，新项目统一用 `streamable-http`，避免未来升级返工。
12. **`spring-ai-alibaba-studio` 是内嵌聊天 UI**：加依赖后访问 `/chatui/index.html` 即可对话；standalone 模式在仓库 `spring-ai-alibaba-studio/agent-chat-ui`（Next.js，`NEXT_PUBLIC_API_URL` / `NEXT_PUBLIC_APP_NAME` 指定后端与 agent 名）。
13. **Java 版本**：SAA/Sandbox 要求 Java 17+，Java 21 可直接用（虚拟线程对 IO 密集的模型调用有利）。
14. **文档缺失区（需读源码）**：SAA 自研 MCP 网关/路由 starter、Agent Framework memory 与 Spring AI `ChatMemory` 的桥接、`ModelResponse` 之外的 usage 聚合工具——官网均无文档。

来源：综合 §1–§8 各页；额外 https://raw.githubusercontent.com/spring-ai-alibaba/website/main/docs/frameworks/studio/quick-start.md
