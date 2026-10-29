package com.agentforge.audit.quota.repository;

import com.agentforge.audit.quota.model.QuotaTierEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 套餐仓储：按编码查询、按排序取列表、统计各套餐使用人数。
 */
public interface QuotaTierRepository extends JpaRepository<QuotaTierEntity, Long> {

    Optional<QuotaTierEntity> findByCode(String code);

    boolean existsByCode(String code);

    List<QuotaTierEntity> findAllByOrderBySortOrderAscIdAsc();

    @Query("select q.tierId, count(q) from UserQuotaEntity q where q.tierId is not null group by q.tierId")
    List<Object[]> countUsersByTier();
}
