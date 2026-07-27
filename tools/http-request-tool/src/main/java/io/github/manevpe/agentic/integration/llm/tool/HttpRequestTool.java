package io.github.manevpe.agentic.integration.llm.tool;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import io.github.manevpe.agentic.tool.ToolBundle;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Generic, credential-free "fetch a URL" tool — e.g. for pages in a
 * team's internal documentation repository/wiki (referenced from domain
 * knowledge as a plain URL) that aren't themselves a GitHub repository
 * file readable via {@code readRepoFile}. Deliberately narrow: GET only,
 * no request body/headers the model can set, a hard response-size cap
 * and short timeout so a single tool call can't hang or blow up context
 * with a huge/binary response.
 *
 * <p>Rate-limited to one active (in-flight) request per calling agent
 * turn — tracked in a {@link ThreadLocal} {@link Semaphore}, the same
 * per-turn scoping {@code WorkspaceSetupTool} uses. This exists for the
 * same reason as that tool's per-turn sandbox-workspace cap: an LLM that
 * decides to "explore broadly" can otherwise issue many tool calls in
 * quick succession, and outbound HTTP requests are cheap enough that
 * nothing else would stop a runaway fan-out. A second fetchUrl call
 * while one is still in flight on the same turn fails fast with a tool
 * error instead of firing concurrently.
 *
 * <p>Unlike {@code GitHubClient}/{@code JiraClient}/{@code SlackClient},
 * this has no credentials and nothing to stub out for tests — the same
 * real implementation runs in every environment, so it implements {@link
 * ToolBundle} directly with no {@code PluginContextAware} wiring.
 */
public class HttpRequestTool implements ToolBundle {

    /** Hard cap on how much of a response body is returned to the model. */
    static final int MAX_RESPONSE_CHARS = 20_000;

    private final RestClient restClient;
    private final ThreadLocal<Semaphore> activeRequestPermit = ThreadLocal.withInitial(() -> new Semaphore(1));

    public HttpRequestTool() {
        this.restClient = RestClient.builder()
                .requestFactory(clientRequestFactory())
                .defaultHeader(HttpHeaders.USER_AGENT, "java-cloud-agent-framework/http-request-tool")
                .build();
    }

    private static ClientHttpRequestFactory clientRequestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
        return factory;
    }

    @Override
    public String name() {
        return "http-request";
    }

    @Override
    public List<ToolCallback> tools() {
        return List.of(ToolCallbacks.from(this));
    }

    @Tool(description = "Fetches a URL via HTTP(S) GET and returns its response body as text (truncated to "
            + "the first " + MAX_RESPONSE_CHARS + " characters). GET only, no custom headers/auth — for "
            + "public or already-authorized-by-network-context pages such as internal documentation. Not a "
            + "substitute for readRepoFile/gitClone when reading files from a GitHub repository. Only one "
            + "request may be in flight at a time — wait for a previous fetchUrl call to return before "
            + "issuing another.")
    public String fetchUrl(@ToolParam(description = "the URL to fetch, must start with http:// or https://") String url) {
        if (url == null || url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("url must be a non-blank http(s):// URL — got: '" + url + "'");
        }
        Semaphore permit = activeRequestPermit.get();
        if (!permit.tryAcquire()) {
            throw new IllegalStateException(
                    "Another fetchUrl call is already in flight for this agent turn — wait for it to "
                            + "return before issuing another request.");
        }
        try {
            String body = restClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .body(String.class);
            String result = ToolResults.orPlaceholder(body, "(empty response body)");
            return result.length() > MAX_RESPONSE_CHARS
                    ? result.substring(0, MAX_RESPONSE_CHARS) + "\n... (truncated)"
                    : result;
        } finally {
            permit.release();
        }
    }
}
