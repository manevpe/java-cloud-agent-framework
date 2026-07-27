package io.github.manevpe.agentic.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A durable, replayable multi-turn conversation between one agent and an
 * LLM (plus, when paused, a human) — the "cloud Copilot CLI" alternative
 * to LangGraph4j's fixed-round gate-node pause/resume pattern (see {@code
 * SlackGateAgent}/{@code PrCommentGateAgent}). Rather than a graph node
 * pausing on a rigid, pre-declared edge, an agent using this model can
 * call {@code askHuman} as many times as it needs (up to its own
 * configured safety cap) and have each reply folded back into the same
 * conversation, replayed in full to the LLM on every continuation — the
 * same way a human resuming a chat session sees its whole history.
 *
 * <p>{@code workflowThreadId} is a loose correlation label (typically the
 * ticket key), not a foreign key into LangGraph4j's own thread/checkpoint
 * tables — the two pause/resume mechanisms are intentionally independent
 * (see ADR-0003).
 */
public record ConversationSession(
        UUID id,
        String workflowThreadId,
        String agentType,
        ConversationStatus status,
        String correlationKey,
        List<ConversationTurn> turns,
        Instant createdAt,
        Instant updatedAt
) {

    public ConversationSession {
        turns = List.copyOf(turns);
    }

    public static ConversationSession start(String workflowThreadId, String agentType, List<ConversationTurn> seedTurns) {
        Instant now = Instant.now();
        return new ConversationSession(
                UUID.randomUUID(), workflowThreadId, agentType, ConversationStatus.ACTIVE,
                null, seedTurns, now, now);
    }

    public ConversationSession withAppendedTurn(ConversationTurn turn) {
        List<ConversationTurn> updated = new ArrayList<>(turns);
        updated.add(turn);
        return new ConversationSession(id, workflowThreadId, agentType, status, correlationKey, updated, createdAt, Instant.now());
    }

    /** Pauses the session: the owning agent called {@code askHuman} and is waiting on {@code correlationKey}. */
    public ConversationSession awaitingHuman(String correlationKey) {
        return new ConversationSession(
                id, workflowThreadId, agentType, ConversationStatus.AWAITING_HUMAN, correlationKey, turns, createdAt, Instant.now());
    }

    /** Resumes the session after a human's reply has been appended as a new turn. */
    public ConversationSession resumed() {
        return new ConversationSession(
                id, workflowThreadId, agentType, ConversationStatus.ACTIVE, null, turns, createdAt, Instant.now());
    }

    /** Marks the session finished — the agent produced its final result. */
    public ConversationSession completed() {
        return new ConversationSession(
                id, workflowThreadId, agentType, ConversationStatus.COMPLETED, null, turns, createdAt, Instant.now());
    }

    /** How many times a human has replied so far — used to enforce a max-rounds safety cap. */
    public long humanReplyCount() {
        return turns.stream().filter(t -> t.role() == ConversationRole.HUMAN).count();
    }
}
