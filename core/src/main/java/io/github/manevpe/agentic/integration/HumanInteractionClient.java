package io.github.manevpe.agentic.integration;

/**
 * Generic port for an agent to pause and ask a human a clarifying
 * question through whatever messaging system a workflow node configures
 * (Slack today; a future Teams/Discord/email adapter registers under a
 * different {@link #provider()} name without touching any agent or tool
 * code). Used by {@code AskHumanTool} — see its Javadoc for why this
 * can't simply block waiting for the reply.
 *
 * <p>Deliberately as narrow as {@link SlackClient}: start a thread/topic
 * with a question, get back an opaque correlation id that a later inbound
 * webhook event resumes the paused workflow node with.
 */
public interface HumanInteractionClient {

    /**
     * The provider name workflow YAML node config selects this
     * implementation by (e.g. {@code humanInteraction.provider: slack}).
     * Must be unique across all registered implementations.
     */
    String provider();

    /**
     * Starts a new question/thread addressed to {@code target} (a Slack
     * channel, a Teams channel id, ...; provider-specific format).
     *
     * @return an opaque correlation id that a later reply is matched
     *         against to resume the paused workflow node
     */
    String ask(String target, String question);
}
