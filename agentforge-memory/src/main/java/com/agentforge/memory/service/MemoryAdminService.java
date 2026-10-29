package com.agentforge.memory.service;

import com.agentforge.common.api.PageResult;
import com.agentforge.common.context.TenantContext;
import com.agentforge.common.context.UserContext;
import com.agentforge.common.exception.BizException;
import com.agentforge.memory.api.MemoryFactView;
import com.agentforge.memory.api.MemoryMessageView;
import com.agentforge.memory.api.MemorySessionSummaryView;
import com.agentforge.memory.spi.LongTermFact;
import com.agentforge.memory.spi.LongTermMemory;
import com.agentforge.memory.spi.MemoryMessage;
import com.agentforge.memory.spi.MemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆管理服务：对外暴露会话记忆与长期记忆的查询/清理接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryAdminService {

    /** 单会话最大回溯窗口（存储层最多保留 200 条）。 */
    private static final int MAX_WINDOW = 200;
    private static final int SUMMARY_MAX_CHARS = 500;

    private final MemoryStore memoryStore;
    private final LongTermMemory longTermMemory;

    public PageResult<MemoryMessageView> listMessages(String sessionId, int page, int size) {
        List<MemoryMessage> messages = memoryStore.getRecentMessages(sessionId, MAX_WINDOW);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        long offset = (long) (safePage - 1) * safeSize;
        int from = (int) Math.min(offset, messages.size());
        int to = Math.min(from + safeSize, messages.size());
        List<MemoryMessageView> records = messages.subList(from, to).stream()
                .map(MemoryMessageView::from)
                .toList();
        return PageResult.of(records, messages.size(), safePage, safeSize);
    }

    public void clearSession(String sessionId) {
        memoryStore.clearSession(sessionId);
        log.info("清理会话记忆: sessionId={}", sessionId);
    }

    public MemorySessionSummaryView summarize(String sessionId) {
        List<MemoryMessage> messages = memoryStore.getRecentMessages(sessionId, MAX_WINDOW);
        Instant lastActiveAt = messages.isEmpty()
                ? null
                : messages.get(messages.size() - 1).createdAt();
        String summary = messages.stream()
                .skip(Math.max(0, messages.size() - 4))
                .map(message -> message.role() + ": " + message.content())
                .collect(Collectors.joining("\n"));
        if (summary.length() > SUMMARY_MAX_CHARS) {
            summary = summary.substring(0, SUMMARY_MAX_CHARS) + "…";
        }
        return new MemorySessionSummaryView(sessionId, messages.size(), summary, lastActiveAt);
    }

    public PageResult<MemoryFactView> listFacts(Long userId, String keyword, int page, int size) {
        long tenantId = TenantContext.getCurrentTenantId();
        Long resolvedUserId = resolveUserId(userId);
        List<LongTermFact> facts = longTermMemory.recall(tenantId, resolvedUserId, keyword, MAX_WINDOW);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        long offset = (long) (safePage - 1) * safeSize;
        int from = (int) Math.min(offset, facts.size());
        int to = Math.min(from + safeSize, facts.size());
        List<MemoryFactView> records = facts.subList(from, to).stream()
                .map(MemoryFactView::from)
                .toList();
        return PageResult.of(records, facts.size(), safePage, safeSize);
    }

    public void deleteFact(Long factId) {
        long tenantId = TenantContext.getCurrentTenantId();
        Long resolvedUserId = resolveUserId(null);
        boolean deleted = longTermMemory.deleteFact(tenantId, resolvedUserId, factId);
        if (!deleted) {
            throw BizException.notFound("记忆不存在或无权访问: " + factId);
        }
        log.info("删除长期记忆: factId={}, userId={}", factId, resolvedUserId);
    }

    private static Long resolveUserId(Long explicitUserId) {
        if (explicitUserId != null) {
            return explicitUserId;
        }
        Long currentUserId = UserContext.getUserIdOrNull();
        return currentUserId == null ? 0L : currentUserId;
    }
}
