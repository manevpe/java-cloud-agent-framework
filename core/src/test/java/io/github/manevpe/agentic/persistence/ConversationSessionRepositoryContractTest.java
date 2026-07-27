package io.github.manevpe.agentic.persistence;

import io.github.manevpe.agentic.conversation.ConversationRole;
import io.github.manevpe.agentic.conversation.ConversationSession;
import io.github.manevpe.agentic.conversation.ConversationStatus;
import io.github.manevpe.agentic.conversation.ConversationTurn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract every {@link ConversationSessionRepository} implementation must satisfy. */
public abstract class ConversationSessionRepositoryContractTest {

    protected abstract ConversationSessionRepository repository();

    @Test
    void savesAndFindsASessionById() {
        ConversationSessionRepository repo = repository();
        ConversationSession session = repo.save(
                ConversationSession.start("PROJ-1", "conversational-planning-agent", List.of()));

        ConversationSession found = repo.findById(session.id()).orElseThrow();
        assertThat(found.workflowThreadId()).isEqualTo("PROJ-1");
        assertThat(found.agentType()).isEqualTo("conversational-planning-agent");
        assertThat(found.status()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(found.turns()).isEmpty();
    }

    @Test
    void appendedTurnsRoundTripInOrder() {
        ConversationSessionRepository repo = repository();
        ConversationSession session = ConversationSession.start("PROJ-2", "conversational-planning-agent", List.of())
                .withAppendedTurn(ConversationTurn.of(ConversationRole.ASSISTANT, "Which metric?"))
                .withAppendedTurn(ConversationTurn.of(ConversationRole.HUMAN, "Total count."));
        repo.save(session);

        ConversationSession found = repo.findById(session.id()).orElseThrow();
        assertThat(found.turns()).extracting(ConversationTurn::role, ConversationTurn::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ConversationRole.ASSISTANT, "Which metric?"),
                        org.assertj.core.groups.Tuple.tuple(ConversationRole.HUMAN, "Total count."));
    }

    @Test
    void findAwaitingByCorrelationKeyLocatesTheWaitingSession() {
        ConversationSessionRepository repo = repository();
        ConversationSession session = ConversationSession.start("PROJ-3", "conversational-planning-agent", List.of())
                .awaitingHuman("slack-thread:789");
        repo.save(session);

        assertThat(repo.findAwaitingByCorrelationKey("slack-thread:789"))
                .map(ConversationSession::id)
                .contains(session.id());
        assertThat(repo.findAwaitingByCorrelationKey("no-such-key")).isEmpty();
    }

    @Test
    void findAwaitingByCorrelationKeyExcludesResumedOrCompletedSessions() {
        ConversationSessionRepository repo = repository();
        ConversationSession awaiting = ConversationSession.start("PROJ-4", "conversational-planning-agent", List.of())
                .awaitingHuman("slack-thread:should-not-match-after-resume");
        repo.save(awaiting);

        ConversationSession resumed = awaiting.resumed();
        repo.save(resumed);

        assertThat(repo.findAwaitingByCorrelationKey("slack-thread:should-not-match-after-resume")).isEmpty();
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertThat(repository().findById(UUID.randomUUID())).isEmpty();
    }
}
