package com.agentforge.datasource.repository;

import com.agentforge.datasource.entity.DataSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 数据源仓储。
 */
public interface DataSourceRepository extends JpaRepository<DataSourceEntity, Long> {
}
