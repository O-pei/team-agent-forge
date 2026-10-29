package com.agentforge.agent.tool;

import com.agentforge.agent.event.AgentEvents;
import com.agentforge.agent.runtime.RunContext;
import com.agentforge.agent.runtime.RunContextHolder;
import com.agentforge.rag.spi.KnowledgeRetriever;
import com.agentforge.rag.spi.RetrievedChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具：只依赖冻结 SPI {@link KnowledgeRetriever}；实现缺失时降级提示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool implements AgentToolDefinition {

    private final ObjectProvider<KnowledgeRetriever> retrieverProvider;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return ToolNames.KNOWLEDGE_SEARCH;
    }

    @Override
    public String description() {
        return "从企业知识库检索与问题相关的文档片段";
    }

    @Override
    public String riskLevel() {
        return "LOW";
    }

    @Override
    public Object toolBean() {
        return this;
    }

    @Tool(name = ToolNames.KNOWLEDGE_SEARCH,
            description = "从企业知识库检索与问题相关的文档片段，返回片段内容、来源文件与相关度")
    public String knowledgeSearch(
            @ToolParam(description = "知识库 ID；不传则检索全部知识库", required = false) Long knowledgeBaseId,
            @ToolParam(description = "检索问题或关键词") String query,
            @ToolParam(description = "返回条数，默认 5，最大 20", required = false) Integer topK) {
        if (query == null || query.isBlank()) {
            return "检索失败: query 不能为空";
        }
        KnowledgeRetriever retriever = retrieverProvider.getIfAvailable();
        if (retriever == null) {
            return "知识检索服务尚未配置（RAG 模块未就绪），请改用其他方式回答或稍后重试。";
        }
        int limit = topK == null || topK <= 0 ? 5 : Math.min(topK, 20);
        double threshold = 0.5d;
        try {
            List<RetrievedChunk> chunks = knowledgeBaseId == null
                    ? retriever.retrieveAll(List.of(), query, limit, threshold)
                    : retriever.retrieve(knowledgeBaseId, query, limit, threshold);
            if (chunks == null || chunks.isEmpty()) {
                return "未检索到相关文档片段。";
            }
            List<Map<String, Object>> results = new ArrayList<>();
            RunContext runContext = RunContextHolder.current();
            for (RetrievedChunk chunk : chunks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("content", chunk.content());
                item.put("score", chunk.score());
                item.put("documentId", chunk.documentId());
                item.put("filename", chunk.filename());
                item.put("chunkIndex", chunk.chunkIndex());
                results.add(item);
                if (runContext != null) {
                    runContext.emit(com.agentforge.agent.event.AgentEvent.CITATION,
                            new AgentEvents.Citation(runContext.nextCitationIndex(), chunk.documentId(),
                                    chunk.filename(), chunk.chunkIndex(), chunk.score()));
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", query);
            payload.put("results", results);
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "检索结果序列化失败: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("knowledge_search 调用失败: {}", e.getMessage());
            return "知识检索失败: " + e.getMessage();
        }
    }
}
