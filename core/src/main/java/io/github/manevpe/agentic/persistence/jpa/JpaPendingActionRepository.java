package io.github.manevpe.agentic.persistence.jpa;

import io.github.manevpe.agentic.persistence.ApprovalStatus;
import io.github.manevpe.agentic.persistence.PendingAction;
import io.github.manevpe.agentic.persistence.PendingActionRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaPendingActionRepository implements PendingActionRepository {

    private final PendingActionJpaRepository jpaRepository;

    JpaPendingActionRepository(PendingActionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public PendingAction save(PendingAction action) {
        PendingActionEntity saved = jpaRepository.save(toEntity(action));
        return toDomain(saved);
    }

    @Override
    public Optional<PendingAction> findById(UUID id) {
        return jpaRepository.findById(id).map(JpaPendingActionRepository::toDomain);
    }

    @Override
    public List<PendingAction> findPendingByThreadId(String threadId) {
        return jpaRepository.findByThreadIdAndStatus(threadId, ApprovalStatus.PENDING).stream()
                .map(JpaPendingActionRepository::toDomain)
                .toList();
    }

    @Override
    public List<PendingAction> findAllPending() {
        return jpaRepository.findByStatus(ApprovalStatus.PENDING).stream()
                .map(JpaPendingActionRepository::toDomain)
                .toList();
    }

    @Override
    public Optional<PendingAction> findPendingByCorrelationKey(String correlationKey) {
        return jpaRepository.findByCorrelationKeyAndStatus(correlationKey, ApprovalStatus.PENDING)
                .map(JpaPendingActionRepository::toDomain);
    }

    private static PendingActionEntity toEntity(PendingAction action) {
        return new PendingActionEntity(
                action.id(), action.threadId(), action.nodeId(), action.actionType(), action.correlationKey(),
                action.payload(), action.status(), action.decidedBy(), action.createdAt(), action.decidedAt());
    }

    private static PendingAction toDomain(PendingActionEntity entity) {
        return new PendingAction(
                entity.getId(), entity.getThreadId(), entity.getNodeId(), entity.getActionType(),
                entity.getCorrelationKey(), entity.getPayload(), entity.getStatus(), entity.getDecidedBy(),
                entity.getCreatedAt(), entity.getDecidedAt());
    }
}
