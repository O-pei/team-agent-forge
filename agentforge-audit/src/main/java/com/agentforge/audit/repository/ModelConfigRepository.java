package com.agentforge.audit.repository;

import com.agentforge.audit.model.ModelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelConfigRepository extends JpaRepository<ModelConfigEntity, Long> {

    List<ModelConfigEntity> findAllByOrderByDefaultModelDescIdAsc();

    Optional<ModelConfigEntity> findFirstByDefaultModelTrueAndEnabledTrueOrderByIdAsc();

    Optional<ModelConfigEntity> findFirstByEnabledTrueOrderByIdAsc();

    long countByEnabledTrue();

    boolean existsByDefaultModelTrue();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
