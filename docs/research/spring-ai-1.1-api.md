# Spring AI 1.1.x 企业级多智能体平台 API 调研（Boot 3.5.x / Java 21）

> 版本基准：**Spring AI 1.1.8**（1.1.x 线最后一个版本，Maven Central 已发布）。官方文档用版本化 URL `https://docs.spring.io/spring-ai/reference/1.1/...`，未版本化 URL 现指向 2.0.2，**不要混用**。
> 文中所有类名/方法名均取自 v1.1.8 官方文档或 `v1.1.8` tag 源码；标注「未确认」的是未逐条核对源码的属性名。

---

## 1. ChatMemory

### 1.1 结论

- `ChatMemory` 是**会话记忆抽象**（不是完整聊天历史，历史请用 Spring Data 自己存）。
- 自动配置默认：`InMemoryChatMemoryRepository` + `MessageWindowChatMemory`。若容器里存在其他 `ChatMemoryRepository` Bean，则替换默认内存实现。
- 1.1.x 内置 Repository：**InMemory / JDBC / Cassandra / Neo4j / MongoDB / CosmosDB**。
- **Redis 的 `ChatMemoryRepository` 在 1.1.x 不存在**：`spring-ai-model-chat-memory-repository-redis` 与 `spring-ai-starter-model-chat-memory-repository-redis` 在 Maven Central 只有 2.0.0-M1 起的版本，v1.1.8 tag 中也没有该模块目录（BOM 1.1.8 里虽声明了坐标，属 pom 与实际发布不一致）。**企业级要 Redis 必须自研**（见 1.4）。
- `MessageWindowChatMemory` 是滑动窗口，默认 `maxMessages=20`，超出时从最旧的开始淘汰，但**保留 SystemMessage**。1.1.8 没有按「对话轮」边界淘汰的逻辑（那是 2.0.x 行为）。
- 记忆必须在 ChatClient 上挂 Advisor 才生效，且**必须传 `ChatMemory.CONVERSATION_ID`**，否则运行时抛 `IllegalArgumentException`（没有默认会话 ID）。

### 1.2 接口（v1.1.8 源码核对）

```java
// org.springframework.ai.chat.memory.ChatMemory
public interface ChatMemory {
    String CONVERSATION_ID = "chat_memory_conversation_id";

    default void add(String conversationId, Message message) {
        add(conversationId, List.of(message));
    }
    void add(String conversationId, List<Message> messages);
    List<Message> get(String conversationId);
    void clear(String conversationId);
}

// org.springframework.ai.chat.memory.ChatMemoryRepository
public interface ChatMemoryRepository {
    List<String> findConversationIds();
    List<Message> findByConversationId(String conversationId);
    void saveAll(String conversationId, List<Message> messages); // 全量替换，不是追加
    void deleteByConversationId(String conversationId);
}
```

### 1.3 MessageWindowChatMemory 与 Advisor 注册

```java
ChatMemory chatMemory = MessageWindowChatMemory.builder()
        .maxMessages(10)                 // 默认 20
        .build();                        // 1.1.8 无 chatMemoryRepository(...) 之外的花样参数

ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .build();

// 调用时必须带会话 ID
String answer = chatClient.prompt()
        .user("继续上一个问题")
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
        .call()
        .content();
```

JDBC 版（生产常用）：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
</dependency>
```

```properties
spring.ai.chat.memory.repository.jdbc.initialize-schema=always   # embedded(默认)|always|never
spring.ai.chat.memory.repository.jdbc.table-name=SPRING_AI_CHAT_MEMORY
```

1.1.8 已知限制：**工具调用的中间消息不会写入记忆**；`PromptChatMemoryAdvisor` 自 1.1.3 起废弃，用 `MessageChatMemoryAdvisor`。

### 1.4 自定义 Redis 实现（1.1.x 唯一可行方案）

关键取舍：**不要用 JDK 序列化，也不要把 `Message` 多态类型直接交给 Jackson**（`UserMessage`/`AssistantMessage`/`ToolResponseMessage` 没有稳定的 `@JsonTypeInfo` 契约）。推荐自己定义扁平 DTO，按会话存一个 Redis List。

```java
package com.example.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String PREFIX = "ai:chat-memory:";
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 扁平存储结构，避免 Message 多态反序列化问题 */
    public record StoredMessage(String type, String text, Map<String, Object> metadata) { }

    @Override
    public List<String> findConversationIds() {
        // 生产环境不要用 keys 扫全库；维护一个 SET ai:chat-memory:index
        var keys = redis.keys(PREFIX + "*");
        if (keys == null) return List.of();
        return keys.stream().map(k -> k.substring(PREFIX.length())).toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> raw = redis.opsForList().range(key(conversationId), 0, -1);
        if (raw == null) return List.of();
        return raw.stream().map(this::toMessage).toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = key(conversationId);
        // 全量替换语义：先删后写，用 MULTI 保证原子性
        redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) conn -> {
            conn.keyCommands().del(key.getBytes());
            if (!messages.isEmpty()) {
                for (Message m : messages) {
                    conn.listCommands().rPush(key.getBytes(), toJson(m).getBytes());
                }
                conn.keyCommands().expire(key.getBytes(), TTL.toSeconds());
            }
            return null;
        });
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(key(conversationId));
    }

    private String key(String conversationId) {
        return PREFIX + conversationId;
    }

    private String toJson(Message message) {
        try {
            return objectMapper.writeValueAsString(
                    new StoredMessage(message.getMessageType().getValue(), message.getText(), message.getMetadata()));
        } catch (Exception e) {
            throw new IllegalStateException("序列化消息失败", e);
        }
    }

    private Message toMessage(String json) {
        try {
            StoredMessage sm = objectMapper.readValue(json, StoredMessage.class);
            return switch (MessageType.fromValue(sm.type())) {
                case USER -> new UserMessage(sm.text());
                case ASSISTANT -> new AssistantMessage(sm.text());
                case SYSTEM -> new SystemMessage(sm.text());
                case TOOL -> throw new IllegalStateException("1.1.x 不建议把 ToolResponseMessage 落库");
            };
        } catch (Exception e) {
            throw new IllegalStateException("反序列化消息失败", e);
        }
    }
}
```

序列化建议（企业实践）：

- `StringRedisTemplate` 使用 `StringRedisSerializer`（key 与 value 都是明文字符串），**禁用 `JdkSerializationRedisSerializer`**：类名进 value，版本升级/改名即炸。
- 需要强 schema 时用 `Jackson2JsonRedisSerializer` + 自己维护的 `@JsonSubTypes` DTO，而不是直接序列化 `Message`。
- 用 Redis List + `MULTI/RPUSH`，一次对话一条 key，天然按会话隔离；用 `EXPIRE` 做 TTL。
- 生产建议加 `ai:chat-memory:index`（SET）记录活跃会话，避免 `KEYS`。
- 注册 `RedisChatMemoryRepository` 为 `@Bean` 后，自动配置的 `InMemoryChatMemoryRepository` 会退让（`@ConditionalOnMissingBean` 语义），但仍显式声明自己的 `ChatMemory` Bean 更可控。

来源：
- https://docs.spring.io/spring-ai/reference/1.1/api/chat-memory.html
- https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/chat/memory/ChatMemoryRepository.java
- Maven Central 目录（证明无 1.1.x 版本）：https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-model-chat-memory-repository-redis/

---

## 2. ChatClient 与 Advisor：统一采集 token usage / 耗时 / 模型名

### 2.1 结论

- 1.1.x 的 Advisor 接口是 `CallAdvisor` / `StreamAdvisor`（**不是** `aroundCall`；旧名 `CallAroundAdvisor.aroundCall(...)` 是 1.0.0-M3 之前的形态，已改名为 `adviseCall`）。
- 一次调用的 usage / 模型名就在 `ChatResponse.getMetadata()` 里：`getUsage()`、`getModel()`、`getId()`。
- 采集点建议做成 `CallAdvisor + StreamAdvisor` 双实现，`getOrder()` 取 `Ordered.HIGHEST_PRECEDENCE + 100`，即**最先进入、最后返回**，能覆盖整条链路耗时（含记忆、RAG Advisor 的开销）。
- 写库必须异步，绝不能阻塞 Reactor 线程（流式接口在事件循环上）。

### 2.2 接口（v1.1.8）

```java
public interface Advisor extends Ordered { String getName(); }

public interface CallAdvisor extends Advisor {
    ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain);
}

public interface StreamAdvisor extends Advisor {
    Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain);
}

// 链
public interface CallAdvisorChain { ChatClientResponse nextCall(ChatClientRequest request); List<CallAdvisor> getCallAdvisors(); }
public interface StreamAdvisorChain { Flux<ChatClientResponse> nextStream(ChatClientRequest request); List<StreamAdvisor> getStreamAdvisors(); }
public interface BaseAdvisor { ChatClientRequest before(ChatClientRequest, AdvisorChain); ChatClientResponse after(ChatClientResponse, AdvisorChain); }
```

顺序规则：`getOrder()` 小的先执行请求、后处理响应（栈式）。同 order 顺序不确定。

### 2.3 采集 Advisor（可编译）

```java
package com.example.ai.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

public class UsageRecordingAdvisor implements CallAdvisor, StreamAdvisor {

    private final UsageRecorder recorder;

    public UsageRecordingAdvisor(UsageRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public String getName() {
        return "usageRecordingAdvisor";
    }

    @Override
    public int getOrder() {
        // 最外层：最先进入、最后返回，计时覆盖整条 advisor 链
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    // ---------- 同步 ----------
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        try {
            ChatClientResponse response = chain.nextCall(request);
            recorder.record(request, response, Duration.ofNanos(System.nanoTime() - start), false, null);
            return response;
        }
        catch (RuntimeException ex) {
            recorder.record(request, null, Duration.ofNanos(System.nanoTime() - start), false, ex);
            throw ex;
        }
    }

    // ---------- 流式 ----------
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.nanoTime();
        StringBuilder text = new StringBuilder();
        AtomicReference<Usage> usageRef = new AtomicReference<>();
        AtomicReference<String> modelRef = new AtomicReference<>();

        return chain.nextStream(request)
                .doOnNext(r -> {
                    ChatResponse cr = r.chatResponse();
                    if (cr == null) {
                        return;
                    }
                    ChatResponseMetadata md = cr.getMetadata();
                    if (md.getModel() != null && !md.getModel().isBlank()) {
                        modelRef.set(md.getModel());
                    }
                    Usage u = md.getUsage();
                    if (u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0) {
                        usageRef.set(u);   // 只有最后一个 usage 块是真实值
                    }
                    if (cr.getResult() != null && cr.getResult().getOutput() != null) {
                        String t = cr.getResult().getOutput().getText();
                        if (t != null) {
                            text.append(t);
                        }
                    }
                })
                .doOnComplete(() -> recorder.recordStream(request, modelRef.get(), usageRef.get(),
                        text.toString(), Duration.ofNanos(System.nanoTime() - start), null))
                .doOnError(ex -> recorder.recordStream(request, modelRef.get(), usageRef.get(),
                        text.toString(), Duration.ofNanos(System.nanoTime() - start), ex));
    }
}
```

```java
package com.example.ai.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
public class UsageRecorder {

    private final JdbcTemplate jdbcTemplate;

    public UsageRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Async("aiUsageExecutor")
    public void record(ChatClientRequest request, ChatClientResponse response,
                       Duration latency, boolean stream, Throwable error) {
        ChatResponse cr = response != null ? response.chatResponse() : null;
        String model = null;
        Integer in = null, out = null, total = null;
        String completion = null;
        String responseId = null;

        if (cr != null) {
            model = cr.getMetadata().getModel();
            responseId = cr.getMetadata().getId();
            Usage usage = cr.getMetadata().getUsage();
            in = usage.getPromptTokens();
            out = usage.getCompletionTokens();
            total = usage.getTotalTokens();
            if (cr.getResult() != null && cr.getResult().getOutput() != null) {
                completion = cr.getResult().getOutput().getText();
            }
        }
        persist(request, model, responseId, in, out, total, completion, latency, stream, error);
    }

    @Async("aiUsageExecutor")
    public void recordStream(ChatClientRequest request, String model, Usage usage, String completion,
                             Duration latency, Throwable error) {
        persist(request, model, null,
                usage != null ? usage.getPromptTokens() : null,
                usage != null ? usage.getCompletionTokens() : null,
                usage != null ? usage.getTotalTokens() : null,
                completion, latency, true, error);
    }

    private void persist(ChatClientRequest request, String model, String responseId,
                         Integer promptTokens, Integer completionTokens, Integer totalTokens,
                         String completion, Duration latency, boolean stream, Throwable error) {
        Map<String, Object> ctx = request.context();
        jdbcTemplate.update("""
                INSERT INTO ai_chat_usage
                  (conversation_id, tenant_id, model, response_id, prompt_tokens, completion_tokens,
                   total_tokens, latency_ms, stream, success, error_msg, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, NOW())
                """,
                String.valueOf(ctx.get("chat_memory_conversation_id")),
                String.valueOf(ctx.get("tenantId")),
                model, responseId, promptTokens, completionTokens, totalTokens,
                latency.toMillis(), stream, error == null, error == null ? null : error.getMessage());
    }
}
```

```sql
CREATE TABLE ai_chat_usage (
  id                BIGSERIAL PRIMARY KEY,
  conversation_id   VARCHAR(128),
  tenant_id         VARCHAR(64),
  model             VARCHAR(128),
  response_id       VARCHAR(128),
  prompt_tokens     INTEGER,
  completion_tokens INTEGER,
  total_tokens      INTEGER,
  latency_ms        BIGINT,
  stream            BOOLEAN,
  success           BOOLEAN,
  error_msg         VARCHAR(1024),
  created_at        TIMESTAMP
);
```

注册（构建期挂载，全局生效）：

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory, UsageRecorder recorder) {
    return builder
            .defaultAdvisors(
                    new UsageRecordingAdvisor(recorder),                       // 最外层计时
                    MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
}
```

要点：

- `ChatResponseMetadata.getUsage()` 默认是 `EmptyUsage`，字段可能为 `0`/`null`，必须容错（上面的 `getTotalTokens() != null && > 0` 即为此）。
- 需要租户/用户维度：请求期传参 `.advisors(a -> a.param("tenantId", tenantId))`，在 Advisor 里读 `request.context()`；`ChatMemory.CONVERSATION_ID` 也可从 context 直接取。
- 想改响应内容（例如洗数据）也能在 `adviseCall` 里 `response.mutate()`；只读采集直接用原对象返回即可。

来源：
- https://docs.spring.io/spring-ai/reference/1.1/api/advisors.html
- https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/api/CallAdvisor.java
- https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/chat/metadata/Usage.java

---

## 3. RAG

### 3.1 结论

- `VectorStore` 只有 5 个操作：`add` / `delete`×3 / `similaritySearch`；写库前用 `TokenTextSplitter` 切分。
- `SimpleVectorStore` **只适合测试/演示**（官方类注释明说 "not designed for production use"），但支持 JSON 文件持久化：`save(File)` / `load(File)` / `load(Resource)`。
- 两种 RAG Advisor：
  - `QuestionAnswerAdvisor`（`spring-ai-advisors-vector-store`）：简单，把检索文档拼进 user text。
  - `RetrievalAugmentationAdvisor`（`spring-ai-rag`）：模块化（Query 变换/扩展、多路检索、DocumentJoiner、QueryAugmenter、后处理），**企业平台首选**。
- 引用（citation）：两个 Advisor 的 `after()` 都会把检索到的文档写进 `ChatResponse.metadata`：
  - `QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS = "qa_retrieved_documents"`
  - `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT = "rag_document_context"`
- 多租户：`VectorStoreDocumentRetriever.builder().filterExpression(Supplier<Filter.Expression>)` 按请求动态生成过滤器；或 `QuestionAnswerAdvisor.FILTER_EXPRESSION` 传参。**FILTER_EXPRESSION 参数优先于构建期默认 filter**（源码 `doGetFilterExpression` 逻辑）。

### 3.2 VectorStore / SearchRequest 接口（v1.1.8 文档核对）

```java
@FunctionalInterface
public interface VectorStoreRetriever {
    List<Document> similaritySearch(SearchRequest request);
    default List<Document> similaritySearch(String query) { ... }
}

public interface VectorStore extends DocumentWriter, VectorStoreRetriever {
    String getName();                                   // default
    void add(List<Document> documents);
    void delete(List<String> idList);
    void delete(Filter.Expression filterExpression);
    void delete(String filterExpression);               // default
    <T> Optional<T> getNativeClient();                  // default
}

public final class SearchRequest {
    public static final double SIMILARITY_THRESHOLD_ACCEPT_ALL = 0.0;
    public static final int DEFAULT_TOP_K = 4;
    // builder(): query(String) 必填, topK(int>=0), similarityThreshold(0..1),
    //            similarityThresholdAll(), filterExpression(String|Filter.Expression)
}
```

过滤 DSL（ANTLR4）：`==` `!=` `>` `>=` `<` `<=`、`AND/and/&&`、`OR/or/||`、`IN/in`、`NIN/nin`、`NOT/not`、`IS NULL`/`IS NOT NULL`（并非所有实现都支持后两者）。

```java
Filter.Expression f = new FilterExpressionBuilder()
        .and(new FilterExpressionBuilder().eq("tenant_id", "t-001"),
             new FilterExpressionBuilder().gte("year", 2024))
        .build();
```

### 3.3 建库（ETL）

依赖（BOM 管版本）：

```xml
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-pdf-document-reader</artifactId></dependency>
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-tika-document-reader</artifactId></dependency>
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-markdown-document-reader</artifactId></dependency>
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-jsoup-document-reader</artifactId></dependency>
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-vector-store</artifactId></dependency>
```

- Word（doc/docx）/PPT/HTML 统一走 `TikaDocumentReader`（没有 POI 专用 reader）。
- PDF 文本型：`PagePdfDocumentReader`（按页）或 `ParagraphPdfDocumentReader`（按目录结构，要求 PDF 有 catalog）。

```java
package com.example.ai.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

public class IngestService {

    private final VectorStore vectorStore;

    public IngestService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingestPdf(String location, String tenantId) {
        List<Document> docs = new PagePdfDocumentReader(
                location,
                PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build())
                .read();

        // 中文必须自定义标点，否则按 '.' '?' '!' 断句会切坏
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(5)
                .withKeepSeparator(true)
                .withPunctuationMarks(List.of('。', '？', '！', '；', '\n'))
                .build();

        List<Document> chunks = splitter.apply(docs);
        chunks.forEach(d -> d.getMetadata().put("tenant_id", tenantId));   // 多租户打标
        vectorStore.add(chunks);
    }

    public void ingestDocx(String location, String tenantId) {
        List<Document> docs = new TikaDocumentReader(location).read();
        docs.forEach(d -> d.getMetadata().put("tenant_id", tenantId));
        vectorStore.add(docs);
    }
}
```

`TokenTextSplitter` 默认值：`chunkSize=800`、`minChunkSizeChars=350`、`minChunkLengthToEmbed=5`、`maxNumChunks=10000`、`keepSeparator=true`、标点 `['.', '?', '!', '\n']`。

### 3.4 SimpleVectorStore（文件持久化）

```java
import org.springframework.ai.vectorstore.SimpleVectorStore;
import java.io.File;

SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
store.add(chunks);
store.save(new File("./vector-store.json"));   // Jackson 序列化全部内容
store.load(new File("./vector-store.json"));   // 或 load(Resource)
```

内部用 `ConcurrentHashMap`，检索走余弦相似度 + 内存过滤，**全量加载进堆**，只适合开发/测试/单机小数据量。

### 3.5 生产向量库 starter 与配置

```xml
<!-- PGVector -->
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-vector-store-pgvector</artifactId></dependency>
<!-- Redis (RediSearch) -->
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-vector-store-redis</artifactId></dependency>
<!-- Qdrant -->
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-vector-store-qdrant</artifactId></dependency>
```

```properties
# PGVector（属性名以官方页面为准，未逐一核对）
spring.ai.vectorstore.pgvector.index-type=HNSW
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
spring.ai.vectorstore.pgvector.dimensions=1024
spring.ai.vectorstore.pgvector.initialize-schema=true
spring.ai.vectorstore.pgvector.table-name=vector_store

# Redis
spring.ai.vectorstore.redis.uri=redis://localhost:6379
spring.ai.vectorstore.redis.index-name=ai-index
spring.ai.vectorstore.redis.prefix=ai:doc:

# Qdrant
spring.ai.vectorstore.qdrant.host=localhost
spring.ai.vectorstore.qdrant.port=6334
spring.ai.vectorstore.qdrant.collection-name=ai_docs
```

### 3.6 QuestionAnswerAdvisor + citation + 多租户

```java
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;   // 注意包名：.advisor.vectorstore
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;

@SuppressWarnings("unchecked")
public Answer ask(String question, String tenantId) {
    ChatResponse response = chatClient.prompt()
            .user(question)
            .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder()
                            .similarityThreshold(0.7)
                            .topK(6)
                            .filterExpression("tenant_id == '" + tenantId + "'")  // 也可放到请求期参数
                            .build())
                    .build())
            .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION,
                                   "tenant_id == '" + tenantId + "'"))
            .call()
            .chatResponse();

    List<Document> retrieved =
            (List<Document>) response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);

    List<Citation> citations = retrieved == null ? List.of() : retrieved.stream()
            .map(d -> new Citation(d.getId(),
                                   String.valueOf(d.getMetadata().get("source")),
                                   String.valueOf(d.getMetadata().get("page_number"))))
            .toList();

    return new Answer(response.getResult().getOutput().getText(), citations);
}

public record Citation(String documentId, String source, String page) { }
public record Answer(String text, List<Citation> citations) { }
```

> 生产建议：租户值不要字符串拼接，用 `FilterExpressionBuilder().eq("tenant_id", tenantId).build()` 传给 `filterExpression(Filter.Expression)`，避免 DSL 注入。

### 3.7 RetrievalAugmentationAdvisor（模块化 RAG，推荐）

```java
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;

@Bean
Advisor ragAdvisor(VectorStore vectorStore) {
    return RetrievalAugmentationAdvisor.builder()
            .documentRetriever(VectorStoreDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .similarityThreshold(0.5)
                    .topK(6)
                    // 动态多租户过滤：每次请求重新求值
                    .filterExpression(() -> new FilterExpressionBuilder()
                            .eq("tenant_id", TenantContext.current()).build())
                    .build())
            .queryAugmenter(ContextualQueryAugmenter.builder()
                    .allowEmptyContext(true)     // false 则检索为空时拒答
                    .build())
            .build();
}
```

运行时拿引用：`response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT)`（`List<Document>`）。

`VectorStoreDocumentRetriever` 的默认值：`similarityThreshold=0.75`、`topK=4`；请求级 `Query.context().get(FILTER_EXPRESSION)` 会覆盖默认 filter。

来源：
- https://docs.spring.io/spring-ai/reference/1.1/api/retrieval-augmented-generation.html
- https://docs.spring.io/spring-ai/reference/1.1/api/etl-pipeline.html
- https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs.html
- https://github.com/spring-projects/spring-ai/blob/v1.1.8/advisors/spring-ai-advisors-vector-store/src/main/java/org/springframework/ai/chat/client/advisor/vectorstore/QuestionAnswerAdvisor.java
- https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-rag/src/main/java/org/springframework/ai/rag/advisor/RetrievalAugmentationAdvisor.java

---

## 4. 流式响应下的 token usage

### 4.1 结论

- 流式接口 `chatClient.prompt()...stream().chatResponse()` 返回 `Flux<ChatResponse>`，**只有最后一个 chunk 带真实 usage**（且该 chunk 的 `choices` 为空数组，`getResult()` 返回 `null`）。
- **OpenAI 兼容服务（含 DeepSeek/vLLM）默认不返回 usage，必须显式打开**
  `spring.ai.openai.chat.options.stream-usage=true`（1.1.8 默认 **false**）。
- 拿不到 usage 的服务：退化为用 Micrometer `gen_ai.client.token.usage` 指标，或自己用 tokenizer（jtokkit）估算。

### 4.2 代码

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;
import java.util.concurrent.atomic.AtomicReference;

public Flux<String> stream(String question) {
    AtomicReference<Usage> lastUsage = new AtomicReference<>();

    return chatClient.prompt()
            .user(question)
            .stream()
            .chatResponse()
            .doOnNext(cr -> {
                Usage u = cr.getMetadata().getUsage();
                if (u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0) {
                    lastUsage.set(u);   // 只在最后一块命中
                }
            })
            .doOnComplete(() -> log.info("usage: prompt={}, completion={}, total={}",
                    lastUsage.get() == null ? null : lastUsage.get().getPromptTokens(),
                    lastUsage.get() == null ? null : lastUsage.get().getCompletionTokens(),
                    lastUsage.get() == null ? null : lastUsage.get().getTotalTokens()))
            .mapNotNull(cr -> cr.getResult() == null ? null : cr.getResult().getOutput().getText());
}
```

需要「聚合后的完整 `ChatClientResponse`」时用官方工具（只读，不能改写响应）：

```java
new ChatClientMessageAggregator().aggregateChatClientResponse(
        chatClient.prompt().user(q).stream().chatResponse(),
        aggregated -> {
            Usage usage = aggregated.chatResponse().getMetadata().getUsage();
            /* 落库 */
        });
```

注意：`stream()` 的 `Flux` 一旦被 `aggregateChatClientResponse` 消费就不能再 `map` 给前端，需要 `share()`/`publish().refCount()` 或两路监听。

来源：
- https://docs.spring.io/spring-ai/reference/1.1/api/chat/openai-chat.html（`spring.ai.openai.chat.options.stream-usage`）
- https://docs.spring.io/spring-ai/reference/1.1/api/advisors.html（`ChatClientMessageAggregator`）

---

## 5. EmbeddingModel 与 OpenAI 兼容接入

### 5.1 内置实现（1.1.8）

| 实现 | Starter 坐标 | 说明 |
|---|---|---|
| OpenAI | `spring-ai-starter-model-openai` | `text-embedding-3-small/large`、`dimensions` 可调 |
| Azure OpenAI | `spring-ai-starter-model-azure-openai` | |
| Ollama | `spring-ai-starter-model-ollama` | 本地 `nomic-embed-text` / `bge-m3` |
| Transformers (ONNX) | `spring-ai-starter-model-transformers` | **纯本地进程内推理**，首次下载模型并缓存 |
| PostgresML | `spring-ai-starter-model-postgresml-embedding` | |
| Mistral AI | `spring-ai-starter-model-mistral-ai` | |
| Vertex AI / Bedrock / Google GenAI / ZhiPuAI / OCI GenAI | 各自 starter | |

**DashScope（通义千问）不在官方 1.1.8 里**：由社区项目 `spring-ai-alibaba` 提供，坐标为 `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`（Maven Central 有 1.1.2.3，对应 Spring AI 1.1.x 线，**版本兼容性未在官方文档确认**）。

### 5.2 接口用法

```java
@Autowired EmbeddingModel embeddingModel;

float[] v1 = embeddingModel.embed("文本");                    // 单条
List<float[]> vs = embeddingModel.embed(List.of("a", "b"));   // 批量
int dim = embeddingModel.dimensions();
```

### 5.3 OpenAI 兼容协议接入 DeepSeek / 自建服务

两种做法，二选一：

**A. 用 openai starter 改 base-url（最通用，任何 OpenAI 兼容网关都能接）**

```properties
spring.ai.openai.base-url=https://api.deepseek.com
spring.ai.openai.api-key=${DEEPSEEK_API_KEY}
spring.ai.openai.chat.completions-path=/v1/chat/completions
spring.ai.openai.chat.options.model=deepseek-chat
spring.ai.openai.chat.options.stream-usage=true
spring.ai.model.chat=openai
```

- 注意：`spring.ai.openai.chat.enabled` 已移除，1.1.x 用 **`spring.ai.model.chat=openai|none`** 控制装配。
- 非标准参数（vLLM/Ollama 私有字段）用 `spring.ai.openai.chat.options.extra-body`，会平铺进请求体；对官方 OpenAI 会 400。
- `reasoning_content`（DeepSeek R1）会被映射到 `AssistantMessage` 的 metadata key `reasoningContent`；流式需自行累加。

**B. 用官方 deepseek starter**

```properties
spring.ai.deepseek.base-url=https://api.deepseek.com
spring.ai.deepseek.api-key=${DEEPSEEK_API_KEY}
spring.ai.deepseek.chat.options.model=deepseek-reasoner
```

```java
DeepSeekChatOptions options = DeepSeekChatOptions.builder()
        .model(DeepSeekApi.ChatModel.DEEPSEEK_REASONER.getValue())
        .build();
DeepSeekAssistantMessage msg = (DeepSeekAssistantMessage) chatResponse.getResult().getOutput();
String reasoning = msg.getReasoningContent();
```

多轮对话时**不要把 reasoning_content 回传给 API**（会 400）。前缀补全用 `DeepSeekAssistantMessage.prefixAssistantMessage("```python\n")` + `stop=["```"]`。

通用重试配置：

```properties
spring.ai.retry.max-attempts=5
spring.ai.retry.backoff.initial-interval=2s
spring.ai.retry.backoff.multiplier=2
spring.ai.retry.backoff.max-interval=1m
spring.ai.retry.on-client-errors=false
```

来源：
- https://docs.spring.io/spring-ai/reference/1.1/api/embeddings.html
- https://docs.spring.io/spring-ai/reference/1.1/api/embeddings/onnx.html
- https://docs.spring.io/spring-ai/reference/1.1/api/chat/openai-chat.html
- https://docs.spring.io/spring-ai/reference/1.1/api/chat/deepseek-chat.html

---

## 6. Tool Calling 与 MCP

### 6.1 结论

- 注解式 `@Tool` + `@ToolParam` 是最省事的写法，Spring AI 自动生成 JSON Schema。
- 注册有三种：`.tools(obj)`（声明式）、`.toolCallbacks(ToolCallback...)`（编程式）、`.toolNames("beanName")`（动态 Bean 解析）。
- **运行时工具完全覆盖默认工具**（不是合并）。
- 工具参数里可以注入 `ToolContext`，用来传租户/用户等**不发给模型**的上下文。
- 异常默认被 `DefaultToolExecutionExceptionProcessor` 吞掉并作为字符串回传给模型；要抛给上层需 `spring.ai.tools.throw-exception-on-error=true`。
- MCP 客户端 starter 已内置，属性前缀 `spring.ai.mcp.client`，工具通过 `SyncMcpToolCallbackProvider` 暴露。

### 6.2 @Tool / ToolContext / 异常

```java
package com.example.ai.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class OrderTools {

    @Tool(description = "根据订单号查询订单状态，返回 JSON")
    public String queryOrder(
            @ToolParam(description = "订单号，形如 SO-2024-0001") String orderNo,
            ToolContext toolContext) {                     // 不会进 schema、不会发给模型

        String tenantId = String.valueOf(toolContext.getContext().get("tenantId"));
        return orderService.findStatus(tenantId, orderNo);
    }

    @Tool(description = "退款，成功后直接返回给用户", returnDirect = true)
    public String refund(@ToolParam(description = "订单号") String orderNo) {
        return refundService.refund(orderNo);
    }
}
```

```java
// 客户端传上下文
chatClient.prompt()
        .user("帮我查 SO-2024-0001")
        .tools(new OrderTools())
        .toolContext(java.util.Map.of("tenantId", "t-001", "userId", "u-9"))
        .call()
        .content();
```

```properties
# 默认 false：工具异常转成字符串喂回模型
spring.ai.tools.throw-exception-on-error=false
```

工具方法注意：不能返回/接收 `Optional`、`Mono/Flux/Flow`、`CompletableFuture/Future`、`Function/Supplier/Consumer`；返回类型必须可序列化（默认 `DefaultToolCallResultConverter` 走 Jackson）。

### 6.3 MCP Client

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>   <!-- WebFlux 场景用 -mcp-client-webflux -->
</dependency>
```

```json
// src/main/resources/mcp-servers.json
{
  "mcpServers": {
    "filesystem": {
      "command": "cmd.exe",
      "args": ["/c", "npx", "-y", "@modelcontextprotocol/server-filesystem", "D:/data"]
    },
    "internal-tools": {
      "url": "http://tools.internal:8080",
      "endpoint": "/mcp"
    }
  }
}
```

```properties
spring.ai.mcp.client.stdio.servers-configuration=classpath:mcp-servers.json
spring.ai.mcp.client.name=my-platform-mcp
spring.ai.mcp.client.version=1.0.0
spring.ai.mcp.client.request-timeout=20s
spring.ai.mcp.client.type=SYNC
spring.ai.mcp.client.toolcallback.enabled=true
spring.ai.mcp.client.initialized=true
```

```java
// MCP 工具自动变成 ToolCallbackProvider（SyncMcpToolCallbackProvider）
@Bean
ChatClient chatClient(ChatClient.Builder builder, ToolCallbackProvider mcpToolCallbackProvider) {
    return builder.defaultToolCallbacks(mcpToolCallbackProvider.getToolCallbacks()).build();
}
```

关键属性：`spring.ai.mcp.client.stdio.connections.<name>.{command,args,env}`、`spring.ai.mcp.client.sse.connections.<name>.{url,sse-endpoint}`、`spring.ai.mcp.client.streamable-http.connections.<name>.{url,endpoint}`。
Windows 上 stdio 必须 `command: cmd.exe` + `args: ["/c", ...]`（`ProcessBuilder` 不能直接执行 `.cmd`）。
`type` 为 `SYNC|ASYNC`，同一应用内不可混用。

来源：
- https://docs.spring.io/spring-ai/reference/1.1/api/tools.html
- https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-client-boot-starter-docs.html

---

## 7. 可观测性（Micrometer / OTel）

### 7.1 结论

- 加 `spring-boot-starter-actuator` 即自动接入 Micrometer `Observation`；`ChatClient`（含所有 Advisor）、`ChatModel`、`EmbeddingModel`、`VectorStore`、`Tool` 五类 observation 都有。
- **`ChatClient` 的 observation 里没有 token 字段**，token 只在 `ChatModel` 层（`gen_ai.usage.*`）。所以「按 ChatClient 调用归集 token」还是要靠第 2 节的 Advisor，或用 `ObservationHandler` 拿 `ChatModel` 的 observation。
- 低基数标签进 metrics + traces，高基数标签只进 traces。

### 7.2 可获取字段

| Observation | 名称 | 关键字段 |
|---|---|---|
| ChatClient | `spring.ai.chat.client` | `gen_ai.operation.name=framework`、`gen_ai.system=spring_ai`、`spring.ai.chat.client.stream`、`spring.ai.kind=chat_client`、`spring.ai.chat.client.advisors`、`spring.ai.chat.client.conversation.id`、`spring.ai.chat.client.tool.names`、`gen_ai.prompt`（需开关） |
| Advisor | `spring.ai.advisor` | `spring.ai.advisor.name`、`spring.ai.advisor.order` |
| ChatModel | `gen_ai.client.operation` | `gen_ai.request.model`、`gen_ai.response.model`、`gen_ai.response.id`、`gen_ai.response.finish_reasons`、`gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`、`gen_ai.usage.total_tokens`、temperature/top_p/max_tokens、`gen_ai.prompt`/`gen_ai.completion` |
| Embedding | `gen_ai.client.operation` | `gen_ai.request.embedding.dimensions`、`gen_ai.usage.input_tokens` |
| VectorStore | `db.vector.client.operation` | `db.operation.name=add\|delete\|query`、`db.system=pg_vector\|redis\|qdrant\|...`、`db.vector.query.top_k`、`db.vector.query.similarity_threshold`、`db.vector.query.filter`、`db.vector.query.response.documents` |
| Tool | `spring.ai.tool` | `spring.ai.tool.definition.name`、`.description`、`.schema`、`spring.ai.tool.call.arguments`、`.result` |

Prometheus 指标名（自动转换）：`gen_ai_client_operation_seconds_{count,sum,max}`、`gen_ai_client_operation_active_count`、`gen_ai_chat_client_operation_seconds_*`、`gen_ai_client_token_usage_total{gen_ai_token_type="input|output|total"}`、`db_vector_client_operation_seconds_*`。

### 7.3 开关配置

```properties
spring.ai.chat.client.observations.log-prompt=false
spring.ai.chat.client.observations.log-completion=false
spring.ai.chat.observations.log-prompt=false
spring.ai.chat.observations.log-completion=false
spring.ai.chat.observations.include-error-logging=false
spring.ai.vectorstore.observations.log-query-response=false
spring.ai.tools.observations.include-content=false
```

> 1.0.0-RC1 起这三个属性由 `include-*` 改名为 `log-*`，老配置会失效。

来源：
- https://docs.spring.io/spring-ai/reference/1.1/observability/index.html

---

## 8. BOM / 仓库 / JDK 基线

### 8.1 结论

- **Spring AI 1.1.x ↔ Spring Boot 3.5.x**（官方支持 3.4.x 与 3.5.x）。2.x 线才对应 Boot 4.x。
- **JDK 基线是 17**（README 原文 "targets and builds artifacts compatible with Java 17+"），Java 21 完全可用，建议 21。
- 1.0.0 起已全部发布到 **Maven Central**，用 1.1.8 **不需要** `spring-milestones` 仓库；只有 snapshot / 旧 milestone 才要。
- BOM 导入即可，starter 不再写版本。

### 8.2 Maven

```xml
<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.1.8</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
</dependencies>
```

### 8.3 Gradle

```groovy
dependencies {
    implementation platform("org.springframework.ai:spring-ai-bom:1.1.8")
    implementation "org.springframework.ai:spring-ai-starter-model-openai"
}
```

### 8.4 仅当需要 snapshot/milestone 时才加仓库

```xml
<repositories>
    <repository>
        <id>spring-snapshots</id>
        <url>https://repo.spring.io/snapshot</url>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
    <repository>
        <id>spring-milestones</id>
        <url>https://repo.spring.io/milestone</url>
    </repository>
</repository>
```

若用 mirror `*` 会把 Spring 仓库也拦掉，需写成 `*,!spring-snapshots,!central-portal-snapshots`。

### 8.5 模型装配开关（1.1 重要变化）

```properties
spring.ai.model.chat=openai          # 或 none / ollama / deepseek / anthropic ...
spring.ai.model.embedding=openai
```

来源：
- https://docs.spring.io/spring-ai/reference/1.1/getting-started.html
- https://github.com/spring-projects/spring-ai/blob/v1.1.8/README.adoc
- https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/1.1.8/spring-ai-bom-1.1.8.pom

---

## 附：踩坑清单

1. **Redis ChatMemory 在 1.1.x 不存在**——见第 1.4 节自研实现；不要照抄 2.0 文档的 `RedisChatMemoryRepository`。
2. **`ChatMemory.CONVERSATION_ID` 必传**，否则运行时 `IllegalArgumentException`。
3. **流式 usage 默认关闭**：`spring.ai.openai.chat.options.stream-usage=true`（默认 false）。
4. **`aroundCall` 已不存在**：1.1 是 `adviseCall` / `adviseStream`。
5. **`spring.ai.openai.chat.enabled` 已移除**，改用 `spring.ai.model.chat`。
6. **Word 文档没有专用 reader**，走 Tika。
7. **`MessageWindowChatMemory` 默认只留 20 条**，多轮长会话必须调大或换 JDBC/Redis 方案。
8. **JdbcChatMemoryRepository 全量替换语义**：`saveAll` 是 delete + insert，不是 append。
9. **工具异常默认不回抛**，线上排障要显式 `spring.ai.tools.throw-exception-on-error=true`。
10. **官方文档默认 URL 已指向 2.0.2**，查 1.1 必须带 `/1.1/`。
