package io.github.manevpe.agentic.persistence;

import io.github.manevpe.agentic.conversation.ConversationSession;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for durable {@link ConversationSession} storage. Deliberately as
 * narrow as {@link PendingActionRepository} — an agent using the
 * conversation-session model only ever needs to save its session, reload
 * it by id on its own next invocation, or (on resume) look it up by the
 * correlation key it's waiting on.
 */
public interface ConversationSessionRepository {

    ConversationSession save(ConversationSession session);

    Optional<ConversationSession> findById(UUID id);

    /** Used to append the human's reply when a correlated external event arrives. */
    Optional<ConversationSession> findAwaitingByCorrelationKey(String correlationKey);
}
