package com.agentforge.audit.quota.repository;

import com.agentforge.audit.quota.model.QuotaGrantLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 手动分配流水仓储：按用户分页、创建时间倒序。
 */
public interface QuotaGrantLogRepository extends JpaRepository<QuotaGrantLogEntity, Long> {

    Page<QuotaGrantLogEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
