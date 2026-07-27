package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code agentic.jira.*} properties for the real, API-token-authenticated
 * {@code JiraClient} (see {@code integration.jira.RestJiraClient}) — Jira
 * Cloud's REST API v3, authenticated via HTTP Basic with an account email
 * + API token (the standard Atlassian Cloud auth model for personal/service
 * accounts; swap for OAuth 2.0 (3LO) later behind the same port if needed).
 *
 * @param enabled  when {@code false} (the default), {@code LoggingJiraClient} is used instead
 * @param baseUrl  the site's base URL, e.g. {@code https://your-domain.atlassian.net}
 * @param email    the Atlassian account email the API token belongs to
 * @param apiToken an Atlassian API token (created at id.atlassian.com/manage-profile/security/api-tokens)
 */
@ConfigurationProperties(prefix = "agentic.jira")
public record JiraProperties(
        boolean enabled,
        String baseUrl,
        String email,
        String apiToken
) {
}
