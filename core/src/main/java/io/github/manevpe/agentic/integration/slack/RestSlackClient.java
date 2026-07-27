package io.github.manevpe.agentic.integration.slack;

import io.github.manevpe.agentic.config.SlackProperties;
import io.github.manevpe.agentic.integration.SlackClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * Real {@link SlackClient}, backed by Slack's Web API {@code
 * chat.postMessage} method — active once {@code agentic.slack.enabled=true}
 * (see {@code LoggingSlackClient}, the default stub). Requires a bot token
 * ({@code agentic.slack.bot-token}, an {@code xoxb-...} token from a Slack
 * app installed in the target workspace with the {@code chat:write}
 * scope).
 *
 * <p>Slack's HTTP API always returns {@code 200 OK} even on failure,
 * signaling errors via a JSON {@code ok: false} + {@code error} field
 * instead of an HTTP status code — {@link #postThread} checks that field
 * explicitly and throws rather than silently swallowing a failed post.
 */
@Component
@ConditionalOnProperty(prefix = "agentic.slack", name = "enabled", havingValue = "true")
public class RestSlackClient implements SlackClient {

    private static final Logger log = LoggerFactory.getLogger(RestSlackClient.class);
    private static final String POST_MESSAGE_URL = "https://slack.com/api/chat.postMessage";

    private final RestClient restClient;
    private final String postMessageUrl;

    @Autowired
    public RestSlackClient(SlackProperties properties) {
        this(properties, "https://slack.com");
    }

    /** Package-visible seam for tests to point at a stub HTTP server instead of the real Slack API. */
    RestSlackClient(SlackProperties properties, String baseUrl) {
        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.botToken())
                .build();
        this.postMessageUrl = baseUrl + "/api/chat.postMessage";
    }

    @Override
    public String postThread(String channel, String text) {
        Map<String, Object> response = restClient.post()
                .uri(postMessageUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("channel", channel, "text", text))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
            String error = response == null ? "empty response" : String.valueOf(response.get("error"));
            throw new IllegalStateException("Slack chat.postMessage failed: " + error);
        }
        String threadTs = String.valueOf(response.get("ts"));
        log.info("Posted Slack thread '{}' in channel '{}'", threadTs, channel);
        return threadTs;
    }
}
