package io.github.manevpe.agentic.integration.slack;

import com.sun.net.httpserver.HttpServer;
import io.github.manevpe.agentic.config.SlackProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link RestSlackClient} against a stub HTTP server rather than
 * the real Slack API — exercises the actual request/response wiring
 * (bearer auth header, JSON body, Slack's {@code ok: false}-signals-error
 * convention) without needing a real workspace/token.
 */
class RestSlackClientTest {

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
    void postThreadReturnsThreadTimestampOnSuccess() throws IOException {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        server.createContext("/api/chat.postMessage", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"ok\":true,\"ts\":\"1234.5678\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        RestSlackClient client = new RestSlackClient(new SlackProperties(true, "xoxb-test"), baseUrl());

        String threadTs = client.postThread("#dev", "hello");

        assertThat(threadTs).isEqualTo("1234.5678");
        assertThat(receivedAuth.get()).isEqualTo("Bearer xoxb-test");
    }

    @Test
    void postThreadThrowsWhenSlackSignalsAnError() throws IOException {
        server.createContext("/api/chat.postMessage", exchange -> {
            byte[] body = "{\"ok\":false,\"error\":\"channel_not_found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        RestSlackClient client = new RestSlackClient(new SlackProperties(true, "xoxb-test"), baseUrl());

        assertThatThrownBy(() -> client.postThread("#dev", "hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel_not_found");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
