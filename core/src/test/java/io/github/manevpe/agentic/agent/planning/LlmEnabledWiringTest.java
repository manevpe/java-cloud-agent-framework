package io.github.manevpe.agentic.agent.planning;

import io.github.manevpe.agentic.integration.LlmClient;
import io.github.manevpe.agentic.integration.llm.SpringAiLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that enabling {@code agentic.llm.enabled=true} wires the real
 * Vertex AI-backed {@link LlmClient} bean instead of the default logging
 * stub — without requiring live GCP credentials, since the Google GenAI
 * {@code Client}/{@code GoogleGenAiChatModel} construction only resolves
 * credentials lazily, on an actual API call (never made by this test).
 *
 * <p>{@code PlanningAgent}/{@code CodingAgent} now live in the {@code
 * agents} plugin module and depend on {@link LlmClient} (real or stub)
 * via {@code PluginContext} rather than Spring constructor injection (see
 * ADR-0009/ADR-0007), so this test only asserts on the {@link LlmClient}
 * port itself resolving to the real implementation — agent/tool wiring is
 * covered by the flow tests in the {@code agents} module instead.
 */
@SpringBootTest(properties = {
        "agentic.llm.enabled=true",
        "agentic.llm.google-genai.project-id=fake-project-for-wiring-test"
})
@Testcontainers
class LlmEnabledWiringTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresTheRealVertexAiBackedLlmClientInsteadOfTheLoggingStub() {
        assertThat(context.getBean(ChatModel.class)).isNotNull();
        assertThat(context.getBean(LlmClient.class)).isInstanceOf(SpringAiLlmClient.class);
    }
}
