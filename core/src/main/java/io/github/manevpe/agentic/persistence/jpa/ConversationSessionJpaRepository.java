package io.github.manevpe.agentic.persistence.jpa;

import io.github.manevpe.agentic.conversation.ConversationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ConversationSessionJpaRepository extends JpaRepository<ConversationSessionEntity, UUID> {

    Optional<ConversationSessionEntity> findByCorrelationKeyAndStatus(String correlationKey, ConversationStatus status);
}
