package io.github.manevpe.agentic.persistence.jpa;

import io.github.manevpe.agentic.conversation.ConversationSession;
import io.github.manevpe.agentic.conversation.ConversationStatus;
import io.github.manevpe.agentic.persistence.ConversationSessionRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaConversationSessionRepository implements ConversationSessionRepository {

    private final ConversationSessionJpaRepository jpaRepository;

    JpaConversationSessionRepository(ConversationSessionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public ConversationSession save(ConversationSession session) {
        ConversationSessionEntity saved = jpaRepository.save(toEntity(session));
        return toDomain(saved);
    }

    @Override
    public Optional<ConversationSession> findById(UUID id) {
        return jpaRepository.findById(id).map(JpaConversationSessionRepository::toDomain);
    }

    @Override
    public Optional<ConversationSession> findAwaitingByCorrelationKey(String correlationKey) {
        return jpaRepository.findByCorrelationKeyAndStatus(correlationKey, ConversationStatus.AWAITING_HUMAN)
                .map(JpaConversationSessionRepository::toDomain);
    }

    private static ConversationSessionEntity toEntity(ConversationSession session) {
        return new ConversationSessionEntity(
                session.id(), session.workflowThreadId(), session.agentType(), session.status(),
                session.correlationKey(), session.turns(), session.createdAt(), session.updatedAt());
    }

    private static ConversationSession toDomain(ConversationSessionEntity entity) {
        return new ConversationSession(
                entity.getId(), entity.getWorkflowThreadId(), entity.getAgentType(), entity.getStatus(),
                entity.getCorrelationKey(), entity.getTurns(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
