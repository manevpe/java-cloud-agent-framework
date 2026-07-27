package io.github.manevpe.agentic.conversation;

/** Who authored a single {@link ConversationTurn}. */
public enum ConversationRole {
    /** The agent's system prompt — always the first turn, set once. */
    SYSTEM,
    /** The initial task framing (ticket summary/description/knowledge) handed to the agent. */
    USER,
    /** A completion the LLM produced, including any turn where it invoked {@code askHuman}. */
    ASSISTANT,
    /** A human's reply to a question the agent asked (e.g. via Slack). */
    HUMAN
}
