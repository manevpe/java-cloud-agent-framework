package io.github.manevpe.agentic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.manevpe.agentic.controller.support.FakeLlmClient;
import io.github.manevpe.agentic.persistence.AuditLogEntry;
import io.github.manevpe.agentic.persistence.AuditLogRepository;
import io.github.manevpe.agentic.persistence.PendingAction;
import io.github.manevpe.agentic.persistence.PendingActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end validation of the conversation-session pause/resume model
 * (see ADR-0003): a Jira-shaped payload starts a workflow using {@code
 * conversational-planning-agent}/{@code conversation-resume-gate} instead
 * of {@code planning-agent}/{@code human-gate}, and — when the (fake)
 * model calls {@code askHuman} — the resume endpoint carries a generic
 * {@code humanReply} back into the paused conversation, which then
 * finalizes the plan on its very next turn.
 *
 * <p>Mirrors {@code WorkflowWebhookControllerTest}'s Slack-clarification
 * test for the older, LangGraph4j-checkpoint-only model, but demonstrates
 * the conversation-session model instead.
 */
@SpringBootTest(properties = {
        "agentic.workflows.directory=src/test/resources/workflows/conversational-planning-test",
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
class ConversationalPlanningFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDirectory(DynamicPropertyRegistry registry) {
        registry.add("agentic.plugins.directory", () -> Paths.get("build/plugins").toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PendingActionRepository pendingActionRepository;

    @Test
    void startsAndCompletesImmediatelyWhenNoQuestionIsAsked() throws Exception {
        Map<String, Object> payload = Map.of(
                "ticketKey", "CONV-1", "summary", "Add widget", "description", "Clear, complete requirements.",
                "labels", List.of("ready-for-dev"));

        String json = mockMvc.perform(post("/webhooks/jira-plan-only-conversational-test/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> body = objectMapper.readValue(json, Map.class);
        String threadId = (String) body.get("threadId");
        assertThat(threadId).isNotBlank();

        List<AuditLogEntry> entries = awaitNodeIds(threadId, 2);
        assertThat(entries).extracting(AuditLogEntry::nodeId).containsExactly("plan", "post-plan");
        assertThat(pendingActionRepository.findPendingByThreadId(threadId)).isEmpty();
    }

    @Test
    void startsPausesForAskHumanThenResumesAndFinalizesThePlan() throws Exception {
        Map<String, Object> payload = Map.of(
                "ticketKey", "CONV-2", "summary", "Add widget",
                "description", "Needs a metric. " + FakeLlmClient.ASK_ONCE_MARKER,
                "labels", List.of("ready-for-dev"));

        String startJson = mockMvc.perform(post("/webhooks/jira-plan-only-conversational-test/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> startBody = objectMapper.readValue(startJson, Map.class);
        String threadId = (String) startBody.get("threadId");

        String correlationKey = awaitOnlyPendingCorrelationKey(threadId);

        List<AuditLogEntry> afterStart = auditLogRepository.findByThreadId(threadId);
        assertThat(afterStart).extracting(AuditLogEntry::nodeId).containsExactly("plan", "conversation-resume-gate");

        Map<String, Object> humanReply = Map.of("humanReply", "Show the total widget count.");
        String resumeJson = mockMvc.perform(post("/webhooks/resume/" + correlationKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(humanReply)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> resumeBody = objectMapper.readValue(resumeJson, Map.class);
        assertThat(resumeBody).containsEntry("resumed", true);

        // conversation-resume-gate loops back into "plan": the conversation's
        // second turn now sees the human's reply in its transcript and
        // FakeLlmClient finalizes instead of asking again — see
        // ConversationalPlanningAgent's Javadoc.
        List<AuditLogEntry> afterResume = awaitNodeIds(threadId, 4);
        assertThat(afterResume).extracting(AuditLogEntry::nodeId)
                .containsExactly("plan", "conversation-resume-gate", "plan", "post-plan");
        AuditLogEntry postPlan = afterResume.get(3);
        assertThat((String) postPlan.payload().get("finalPlan")).contains("Conversational plan drafted");
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

    private String awaitOnlyPendingCorrelationKey(String threadId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<PendingAction> pending = pendingActionRepository.findPendingByThreadId(threadId);
            if (pending.size() == 1) {
                String correlationKey = pending.get(0).correlationKey();
                assertThat(correlationKey).isNotBlank();
                return correlationKey;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for exactly one pending action for thread " + threadId);
    }
}
