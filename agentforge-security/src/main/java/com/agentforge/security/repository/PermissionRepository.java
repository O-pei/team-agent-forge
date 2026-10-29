package com.agentforge.security.repository;

import com.agentforge.security.entity.PermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PermissionRepository extends JpaRepository<PermissionEntity, Long> {

    List<PermissionEntity> findAllByOrderByResourceAscActionAsc();
}
