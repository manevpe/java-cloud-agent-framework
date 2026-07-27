package io.github.manevpe.agentic.persistence.jpa;

import io.github.manevpe.agentic.persistence.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PendingActionJpaRepository extends JpaRepository<PendingActionEntity, UUID> {

    List<PendingActionEntity> findByThreadIdAndStatus(String threadId, ApprovalStatus status);

    List<PendingActionEntity> findByStatus(ApprovalStatus status);

    Optional<PendingActionEntity> findByCorrelationKeyAndStatus(String correlationKey, ApprovalStatus status);
}
