package com.agentforge.audit.quota.repository;

import com.agentforge.audit.quota.model.UserQuotaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 用户额度仓储：按用户读取、批量读取与套餐引用判断。
 */
public interface UserQuotaRepository extends JpaRepository<UserQuotaEntity, Long> {

    Optional<UserQuotaEntity> findByUserId(Long userId);

    List<UserQuotaEntity> findByUserIdIn(Collection<Long> userIds);

    boolean existsByTierId(Long tierId);

    long countByTierId(Long tierId);
}
