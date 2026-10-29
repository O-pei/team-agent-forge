package com.agentforge.audit.repository;

import com.agentforge.audit.model.TokenUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TokenUsageRepository extends JpaRepository<TokenUsageEntity, Long>,
        JpaSpecificationExecutor<TokenUsageEntity> {

    /**
     * 汇总：prompt/completion/total tokens、总成本、请求数。
     *
     * <p>多列聚合行必须用 {@link List} 接收：Spring Data 把 {@code Object[]} 返回值当“行数组”
     * （collection-like）处理，单行多列会被包成 {@code Object[][]}，直接取 {@code Object[]}
     * 得到长度 1 的数组，导致 summary/overview 汇总字段全部读成 0。改用
     * {@code List<Object[]>} 后取首行才是真正的 5 列聚合结果。</p>
     */
    @Query("""
            select coalesce(sum(u.promptTokens), 0),
                   coalesce(sum(u.completionTokens), 0),
                   coalesce(sum(u.totalTokens), 0),
                   coalesce(sum(u.cost), 0),
                   count(u)
            from TokenUsageEntity u
            where u.createdAt >= :from and u.createdAt < :to
            """)
    List<Object[]> summarize(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 趋势/分解所需的原始行：createdAt, promptTokens, completionTokens, totalTokens, cost, modelName, agentId, userId。 */
    @Query("""
            select u.createdAt, u.promptTokens, u.completionTokens, u.totalTokens, u.cost,
                   u.modelName, u.agentId, u.userId
            from TokenUsageEntity u
            where u.createdAt >= :from and u.createdAt < :to
            order by u.createdAt asc
            """)
    List<Object[]> findRows(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
