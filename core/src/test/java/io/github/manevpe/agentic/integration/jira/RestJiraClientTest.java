package io.github.manevpe.agentic.integration.jira;

import com.sun.net.httpserver.HttpServer;
import io.github.manevpe.agentic.config.JiraProperties;
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
 * Verifies {@link RestJiraClient} against a stub HTTP server rather than a
 * real Jira Cloud site — exercises the actual request wiring (HTTP Basic
 * auth header, the Atlassian Document Format comment body v3 requires).
 */
class RestJiraClientTest {

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
    void postCommentSendsBasicAuthAndAdfBody() throws IOException {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server.createContext("/rest/api/3/issue/TEST-1/comment", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();

        JiraProperties properties = new JiraProperties(true, "http://localhost:" + port, "me@example.com", "token123");
        RestJiraClient client = new RestJiraClient(properties);

        client.postComment("TEST-1", "The plan is ready.");

        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("me@example.com:token123".getBytes(StandardCharsets.UTF_8));
        assertThat(receivedAuth.get()).isEqualTo(expectedAuth);
        assertThat(receivedPath.get()).isEqualTo("/rest/api/3/issue/TEST-1/comment");
        assertThat(receivedBody.get())
                .contains("\"type\":\"doc\"")
                .contains("The plan is ready.");
    }
}
