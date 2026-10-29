package com.agentforge.memory.api;

import com.agentforge.memory.spi.MemoryMessage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 会话消息视图。
 */
@Schema(description = "会话消息")
public record MemoryMessageView(
        @Schema(description = "角色：user/assistant/system/tool") String role,
        @Schema(description = "消息内容") String content,
        @Schema(description = "创建时间") Instant createdAt) {

    public static MemoryMessageView from(MemoryMessage message) {
        return new MemoryMessageView(message.role(), message.content(), message.createdAt());
    }
}
