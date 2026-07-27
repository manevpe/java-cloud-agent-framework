package io.github.manevpe.agentic.persistence.jpa;

import io.github.manevpe.agentic.persistence.AuditLogEntry;
import io.github.manevpe.agentic.persistence.AuditLogRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
class JpaAuditLogRepository implements AuditLogRepository {

    private final AuditLogJpaRepository jpaRepository;

    JpaAuditLogRepository(AuditLogJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public AuditLogEntry append(AuditLogEntry entry) {
        AuditLogEntityJpa saved = jpaRepository.save(toEntity(entry));
        return toDomain(saved);
    }

    @Override
    public List<AuditLogEntry> findByThreadId(String threadId) {
        return jpaRepository.findByThreadId(threadId).stream()
                .map(JpaAuditLogRepository::toDomain)
                .toList();
    }

    private static AuditLogEntityJpa toEntity(AuditLogEntry entry) {
        return new AuditLogEntityJpa(
                entry.id(), entry.threadId(), entry.nodeId(), entry.actor(),
                entry.actionType(), entry.payload(), entry.approvalStatus(), entry.occurredAt());
    }

    private static AuditLogEntry toDomain(AuditLogEntityJpa entity) {
        return new AuditLogEntry(
                entity.getId(), entity.getThreadId(), entity.getNodeId(), entity.getActor(),
                entity.getActionType(), entity.getPayload(), entity.getApprovalStatus(), entity.getOccurredAt());
    }
}
