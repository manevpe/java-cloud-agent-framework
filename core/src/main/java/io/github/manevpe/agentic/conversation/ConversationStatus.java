package io.github.manevpe.agentic.conversation;

/**
 * Lifecycle of a {@link ConversationSession}. Deliberately narrow — this is
 * not a general state machine, just enough to route a workflow edge
 * ({@code conversationAwaitingHuman}/{@code conversationComplete} in {@code
 * WorkflowConditions}) after the owning agent runs.
 */
public enum ConversationStatus {
    /** Actively being worked on (or just resumed) by the owning agent. */
    ACTIVE,
    /** Paused: the agent called {@code askHuman} and is waiting on a reply. */
    AWAITING_HUMAN,
    /** The agent produced its final result; nothing further to do. */
    COMPLETED
}
