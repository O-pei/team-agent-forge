package com.agentforge.audit.repository;

import com.agentforge.audit.model.ModelPriceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModelPriceRepository extends JpaRepository<ModelPriceEntity, Long> {

    Optional<ModelPriceEntity> findFirstByProviderIgnoreCaseAndModelNameIgnoreCase(String provider, String modelName);
}
