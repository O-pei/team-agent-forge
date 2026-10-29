package com.agentforge.security.repository;

import com.agentforge.security.entity.EmailVerificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerificationEntity, Long> {

    Optional<EmailVerificationEntity> findTopByEmailAndSceneAndConsumedFalseOrderByCreatedAtDesc(String email, String scene);

    boolean existsByEmailAndSceneAndCreatedAtAfter(String email, String scene, LocalDateTime after);

    long countByEmailAndSceneAndCreatedAtAfter(String email, String scene, LocalDateTime after);
}
