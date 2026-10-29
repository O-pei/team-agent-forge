package com.agentforge.memory.api;

import com.agentforge.memory.spi.LongTermFact;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 长期记忆视图。
 */
@Schema(description = "长期记忆")
public record MemoryFactView(
        @Schema(description = "记忆 ID") Long id,
        @Schema(description = "事实/偏好内容") String fact,
        @Schema(description = "分类") String category,
        @Schema(description = "召回得分 0~1") double score,
        @Schema(description = "创建时间") Instant createdAt) {

    public static MemoryFactView from(LongTermFact fact) {
        return new MemoryFactView(fact.id(), fact.fact(), fact.category(), fact.score(), fact.createdAt());
    }
}
