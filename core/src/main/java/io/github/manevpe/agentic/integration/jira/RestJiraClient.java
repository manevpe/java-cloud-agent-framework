package io.github.manevpe.agentic.integration.jira;

import io.github.manevpe.agentic.config.JiraProperties;
import io.github.manevpe.agentic.integration.JiraClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Real {@link JiraClient}, backed by Jira Cloud's REST API v3 ({@code
 * POST /rest/api/3/issue/{key}/comment}) — active once {@code
 * agentic.jira.enabled=true} (see {@code LoggingJiraClient}, the default
 * stub). Authenticates via HTTP Basic with an Atlassian account email +
 * API token — the standard auth model for a personal/service account
 * against Jira Cloud (see {@code JiraProperties}).
 *
 * <p>The comment {@code text} is wrapped as a single-paragraph Atlassian
 * Document Format (ADF) body, the only body shape the v3 comment endpoint
 * accepts — plain-string bodies (allowed by the older v2 API) are
 * rejected by v3.
 */
@Component
@ConditionalOnProperty(prefix = "agentic.jira", name = "enabled", havingValue = "true")
public class RestJiraClient implements JiraClient {

    private static final Logger log = LoggerFactory.getLogger(RestJiraClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public RestJiraClient(JiraProperties properties) {
        this.baseUrl = properties.baseUrl();
        String credentials = properties.email() + ":" + properties.apiToken();
        String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        this.restClient = RestClient.builder()
                .defaultHeader("Authorization", "Basic " + basicAuth)
                .build();
    }

    @Override
    public void postComment(String ticketKey, String text) {
        Map<String, Object> adfBody = Map.of(
                "type", "doc",
                "version", 1,
                "content", List.of(Map.of(
                        "type", "paragraph",
                        "content", List.of(Map.of("type", "text", "text", text))
                )));

        restClient.post()
                .uri(baseUrl + "/rest/api/3/issue/{key}/comment", ticketKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", adfBody))
                .retrieve()
                .toBodilessEntity();

        log.info("Posted comment on Jira ticket '{}'", ticketKey);
    }
}
