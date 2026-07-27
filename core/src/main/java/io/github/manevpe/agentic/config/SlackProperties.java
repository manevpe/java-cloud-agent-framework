package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code agentic.slack.*} properties for the real, bot-token-authenticated
 * {@code SlackClient} (see {@code integration.slack.RestSlackClient}) — Slack's
 * Web API ({@code chat.postMessage}), authenticated via a bot token
 * ({@code xoxb-...}) from a Slack app installed in the workspace, with the
 * {@code chat:write} scope.
 *
 * @param enabled  when {@code false} (the default), {@code LoggingSlackClient} is used instead
 * @param botToken the Slack bot token ({@code xoxb-...})
 */
@ConfigurationProperties(prefix = "agentic.slack")
public record SlackProperties(
        boolean enabled,
        String botToken
) {
}
