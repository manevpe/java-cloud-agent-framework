package io.github.manevpe.agentic.integration;

/**
 * Port to Slack. Kept deliberately narrow to what the framework currently
 * needs — starting a new thread to ask developers a clarifying question
 * (the "grill-me"-style clarifier), similar to a human-in-the-loop gate.
 */
public interface SlackClient {

    /**
     * Posts a new top-level message in {@code channel}, starting a thread.
     *
     * @return an opaque thread identifier (e.g. Slack's {@code thread_ts})
     *         that later replies will be correlated against.
     */
    String postThread(String channel, String text);
}
