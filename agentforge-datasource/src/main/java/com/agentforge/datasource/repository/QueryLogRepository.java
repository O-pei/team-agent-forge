package com.agentforge.datasource.repository;

import com.agentforge.datasource.entity.QueryLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 数据源查询日志仓储。
 */
public interface QueryLogRepository extends JpaRepository<QueryLogEntity, Long> {

    Page<QueryLogEntity> findByDatasourceId(Long datasourceId, Pageable pageable);
}
