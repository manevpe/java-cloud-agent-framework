package io.github.manevpe.agentic.integration.github;

import com.sun.net.httpserver.HttpServer;
import io.github.manevpe.agentic.config.GitHubProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link RestGitHubClient}'s pure-REST operations (reading a
 * file, posting a PR comment) against a stub HTTP server — the
 * JGit-backed clone/branch/commit/push operations
 * ({@link RestGitHubClient#pushBranchAndOpenPullRequest}/{@link
 * RestGitHubClient#pushAmendingCommit}) need a real git remote and are
 * instead exercised manually per {@code docs/local-testing.md}.
 */
class RestGitHubClientTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readFileDecodesBase64ContentFromGitHubContentsApi() throws IOException {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        String expectedContent = "hello world";
        server.createContext("/repos/acme/example-service/contents/README.md", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String base64 = Base64.getEncoder().encodeToString(expectedContent.getBytes(StandardCharsets.UTF_8));
            byte[] body = ("{\"content\":\"" + base64 + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        RestGitHubClient client = new RestGitHubClient(githubProperties());

        String content = client.readFile("acme/example-service", "README.md", null);

        assertThat(content).isEqualTo(expectedContent);
        assertThat(receivedAuth.get()).isEqualTo("Bearer token123");
    }

    @Test
    void postPullRequestCommentPostsToTheIssueCommentsEndpoint() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server.createContext("/repos/acme/example-service/issues/42/comments", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();

        RestGitHubClient client = new RestGitHubClient(githubProperties());

        client.postPullRequestComment("acme/example-service", "https://github.com/acme/example-service/pull/42",
                "Looks good.");

        assertThat(receivedBody.get()).contains("Looks good.");
    }

    private GitHubProperties githubProperties() {
        return new GitHubProperties(true, "token123", "http://localhost:" + port, "http://localhost:" + port, null, null);
    }
}
