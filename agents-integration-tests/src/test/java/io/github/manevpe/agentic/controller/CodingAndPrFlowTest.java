package io.github.manevpe.agentic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.manevpe.agentic.controller.support.FakeLlmClient;
import io.github.manevpe.agentic.persistence.AuditLogEntry;
import io.github.manevpe.agentic.persistence.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end validation of the coding flow: once a plan is finalized (no
 * open questions), {@code CodingAgent}'s {@code implement} mode runs its
 * own LLM tool-calling loop (clone, explore, write, run, diff — see {@code
 * FileEditTool}/{@code WorkspaceSetupTool}) synchronously, in-process,
 * against a sandbox workspace pod, then opens a PR via the stubbed {@code
 * GitHubClient}. This replaced the earlier design where {@code
 * CodingAgent} dispatched an opaque Kubernetes {@code Job} and blocked
 * waiting for its callback — {@link FakeLlmClient} substitutes for a real
 * model so this test can exercise that whole code path deterministically,
 * without needing model credentials or a real cluster.
 *
 * <p>Runs as an ordinary {@code core} {@code @SpringBootTest} (the real
 * app class is on this module's test classpath via {@code
 * testImplementation(project(":core"))}), with the real, migrated {@code
 * CodingAgent}/{@code PlanningAgent}/etc. loaded exactly like any other
 * plugin — via {@code PluginManager}'s {@code ServiceLoader} mechanism
 * against a directory aggregating every out-of-the-box agent/tool jar
 * (see {@code agentic.plugins.directory} below and the {@code
 * aggregatePlugins} task in {@code agents-integration-tests/build.gradle.kts},
 * which copies each sibling module's {@code :jar} output there), not
 * Spring component-scanning — see ADR-0009/ADR-0007.
 */
@SpringBootTest(properties = {
        "agentic.workflows.directory=src/test/resources/workflows/coding-pr-test",
        "agentic.llm.enabled=true",
        // Use the API-key auth path (a fake key is fine — FakeLlmClient
        // intercepts every call, so it's never actually sent) rather than
        // the Vertex AI project-id path: the latter makes the
        // com.google.genai.Client eagerly resolve Application Default
        // Credentials at bean-creation time, which only succeeds on a
        // machine with `gcloud auth application-default login` already
        // run — never true on a clean CI runner.
        "agentic.llm.google-genai.api-key=fake-api-key-for-test"
})
@AutoConfigureMockMvc
@Import(FakeLlmClient.Config.class)
@Testcontainers
class CodingAndPrFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDirectory(DynamicPropertyRegistry registry) {
        registry.add("agentic.plugins.directory", () -> Paths.get("build/plugins").toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void implementRunsTheToolCallingLoopThenOpensPr() throws Exception {
        Map<String, Object> payload = Map.of(
                "ticketKey", "PROJ-10", "summary", "Add widget", "description", "Clear, complete requirements.",
                "labels", List.of("ready-for-dev"));

        String json = mockMvc.perform(post("/webhooks/jira-to-pr-test/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> body = objectMapper.readValue(json, Map.class);
        String threadId = (String) body.get("threadId");
        assertThat(threadId).isNotBlank();

        List<AuditLogEntry> afterImplement = awaitNodeIds(threadId, 3);
        assertThat(afterImplement).extracting(AuditLogEntry::nodeId)
                .containsExactly("plan", "post-plan", "implement");

        AuditLogEntry implementEntry = afterImplement.get(2);
        assertThat((String) implementEntry.payload().get("prUrl")).contains("acme/example-service");
    }

    private List<AuditLogEntry> awaitNodeIds(String threadId, int minSize) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<AuditLogEntry> entries;
        do {
            entries = auditLogRepository.findByThreadId(threadId);
            if (entries.size() >= minSize) {
                return entries;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return entries;
    }
}
