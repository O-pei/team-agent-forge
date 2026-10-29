package com.agentforge.security.repository;

import com.agentforge.security.entity.RolePermissionEntity;
import com.agentforge.security.entity.RolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 角色-权限关联仓储。复合主键字段必须通过 {@code rp.id.roleId} 显式访问。
 */
public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, RolePermissionId> {

    @Query("select rp from RolePermissionEntity rp where rp.id.roleId = :roleId")
    List<RolePermissionEntity> findByRoleId(@Param("roleId") Long roleId);

    @Query("select rp from RolePermissionEntity rp where rp.id.roleId in :roleIds")
    List<RolePermissionEntity> findByRoleIdIn(@Param("roleIds") Collection<Long> roleIds);

    @Modifying
    @Query("delete from RolePermissionEntity rp where rp.id.roleId = :roleId")
    void deleteByRoleId(@Param("roleId") Long roleId);
}
