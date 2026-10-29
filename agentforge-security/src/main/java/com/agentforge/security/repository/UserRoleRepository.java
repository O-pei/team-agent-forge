package com.agentforge.security.repository;

import com.agentforge.security.entity.UserRoleEntity;
import com.agentforge.security.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 用户-角色关联仓储。复合主键字段必须通过 {@code ur.id.userId} 显式访问（Spring Data 无法自动推导 id 属性）。
 */
public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UserRoleId> {

    @Query("select ur from UserRoleEntity ur where ur.id.userId = :userId")
    List<UserRoleEntity> findByUserId(@Param("userId") Long userId);

    @Query("select ur from UserRoleEntity ur where ur.id.userId in :userIds")
    List<UserRoleEntity> findByUserIdIn(@Param("userIds") Collection<Long> userIds);

    @Query("select count(ur) from UserRoleEntity ur where ur.id.roleId = :roleId")
    long countByIdRoleId(@Param("roleId") Long roleId);

    @Modifying
    @Query("delete from UserRoleEntity ur where ur.id.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
