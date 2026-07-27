package io.github.manevpe.agentic.integration.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingSandboxWorkspaceClientTest {

    private final LoggingSandboxWorkspaceClient client = new LoggingSandboxWorkspaceClient();

    @Test
    void openReturnsAUsableFakeHandle() {
        var handle = client.open("acme/example-service", "main");

        assertThat(handle.repository()).isEqualTo("acme/example-service");
        assertThat(handle.workspaceId()).startsWith("stub-");
    }

    @Test
    void readListAndSearchReturnExplanatoryPlaceholders() {
        var handle = client.open("acme/example-service", null);

        assertThat(client.readFile(handle.workspaceId(), "README.md")).contains("No real sandbox configured");
        assertThat(client.listFiles(handle.workspaceId(), "src", null))
                .anySatisfy(line -> assertThat(line).contains("No real sandbox configured"));
        assertThat(client.search(handle.workspaceId(), "TODO"))
                .anySatisfy(line -> assertThat(line).contains("No real sandbox configured"));
    }

    @Test
    void closeDoesNotThrow() {
        var handle = client.open("acme/example-service", null);

        client.close(handle.workspaceId());
    }
}
