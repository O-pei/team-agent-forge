package com.agentforge.memory.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 会话摘要视图。
 */
@Schema(description = "会话摘要")
public record MemorySessionSummaryView(
        @Schema(description = "会话 ID") String sessionId,
        @Schema(description = "消息条数") long messageCount,
        @Schema(description = "摘要文本") String summary,
        @Schema(description = "最后活跃时间") Instant lastActiveAt) {
}
