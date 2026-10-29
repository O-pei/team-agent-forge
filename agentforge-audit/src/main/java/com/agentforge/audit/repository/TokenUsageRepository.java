package com.agentforge.audit.repository;

import com.agentforge.audit.model.TokenUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface TokenUsageRepository extends JpaRepository<TokenUsageEntity, Long>,
        JpaSpecificationExecutor<TokenUsageEntity> {

    /**
     * 汇总 prompt/completion/total tokens、总成本与请求数。
     * 返回 List 后取首行；用单元素 Object[] 会被 Spring Data 当作行数组包装，导致汇总全 0。
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

    /** 趋势与分解所需原始行：createdAt、promptTokens、completionTokens、totalTokens、cost、modelName、agentId、userId。 */
    @Query("""
            select u.createdAt, u.promptTokens, u.completionTokens, u.totalTokens, u.cost,
                   u.modelName, u.agentId, u.userId
            from TokenUsageEntity u
            where u.createdAt >= :from and u.createdAt < :to
            order by u.createdAt asc
            """)
    List<Object[]> findRows(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 用户当日已用 Token（total_tokens 求和）。
     */
    @Query("""
            select coalesce(sum(u.totalTokens), 0L)
            from TokenUsageEntity u
            where u.userId = :userId and u.createdAt >= :from
            """)
    Long sumTotalTokensByUserSince(@Param("userId") Long userId, @Param("from") LocalDateTime from);

    /**
     * 用户全部历史已用 Token。
     */
    @Query("""
            select coalesce(sum(u.totalTokens), 0L)
            from TokenUsageEntity u
            where u.userId = :userId
            """)
    Long sumTotalTokensByUser(@Param("userId") Long userId);

    /**
     * 批量查询用户当日已用 Token，用于用户额度列表避免逐用户查询。
     */
    @Query("""
            select u.userId, coalesce(sum(u.totalTokens), 0L)
            from TokenUsageEntity u
            where u.userId in :userIds and u.createdAt >= :from
            group by u.userId
            """)
    List<Object[]> sumTotalTokensByUserIdsSince(@Param("userIds") Collection<Long> userIds,
                                                @Param("from") LocalDateTime from);

    /**
     * 批量查询用户全部已用 Token。
     */
    @Query("""
            select u.userId, coalesce(sum(u.totalTokens), 0L)
            from TokenUsageEntity u
            where u.userId in :userIds
            group by u.userId
            """)
    List<Object[]> sumTotalTokensByUserIds(@Param("userIds") Collection<Long> userIds);
}
