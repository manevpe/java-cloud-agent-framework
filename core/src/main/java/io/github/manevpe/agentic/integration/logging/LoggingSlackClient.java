package io.github.manevpe.agentic.integration.logging;

import io.github.manevpe.agentic.integration.SlackClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Placeholder {@link SlackClient} that logs instead of calling the real
 * Slack API. Active by default ({@code agentic.slack.enabled} unset or
 * {@code false}) — safe for local dev/tests without a real Slack
 * workspace. Swap by enabling {@code agentic.slack.enabled=true} (plus
 * {@code agentic.slack.bot-token}), which activates {@code
 * RestSlackClient} instead. Returns a synthetic thread id so pause/resume
 * can still be exercised end-to-end without a real Slack workspace.
 */
@Component
@ConditionalOnProperty(prefix = "agentic.slack", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LoggingSlackClient implements SlackClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingSlackClient.class);

    @Override
    public String postThread(String channel, String text) {
        String threadId = "slack-thread-" + UUID.randomUUID();
        log.info("[stub] Would post to Slack channel '{}' (thread {}):\n{}", channel, threadId, text);
        return threadId;
    }
}
