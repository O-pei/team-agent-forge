package com.agentforge.security.repository;

import com.agentforge.security.entity.LoginLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginLogRepository extends JpaRepository<LoginLogEntity, Long> {

    Page<LoginLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
