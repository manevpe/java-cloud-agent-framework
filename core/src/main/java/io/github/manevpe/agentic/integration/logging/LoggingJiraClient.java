package io.github.manevpe.agentic.integration.logging;

import io.github.manevpe.agentic.integration.JiraClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder {@link JiraClient} that logs instead of calling the real
 * Jira REST API. Active by default ({@code agentic.jira.enabled} unset or
 * {@code false}) — safe for local dev/tests without real Jira credentials.
 * Swap by enabling {@code agentic.jira.enabled=true} (plus {@code
 * agentic.jira.base-url}/{@code email}/{@code api-token}), which activates
 * {@code RestJiraClient} instead.
 */
@Component
@ConditionalOnProperty(prefix = "agentic.jira", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LoggingJiraClient implements JiraClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingJiraClient.class);

    @Override
    public void postComment(String ticketKey, String text) {
        log.info("[stub] Would post comment on Jira ticket '{}':\n{}", ticketKey, text);
    }
}
