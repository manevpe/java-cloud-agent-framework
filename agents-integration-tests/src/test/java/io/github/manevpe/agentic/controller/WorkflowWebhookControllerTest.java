package io.github.manevpe.agentic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.manevpe.agentic.persistence.AuditLogEntry;
import io.github.manevpe.agentic.persistence.AuditLogRepository;
import io.github.manevpe.agentic.persistence.PendingAction;
import io.github.manevpe.agentic.persistence.PendingActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * End-to-end validation of the webhook ingress: a Jira-shaped
 * payload starts a workflow, the real {@code planning-agent} decides
 * whether clarification is needed, and — when it is — the resume endpoint
 * carries a Slack-shaped answer back into the paused thread.
 *
 * <p>Uses {@link MockMvc} rather than {@code TestRestTemplate}: Spring Boot
 * 4.1 relocated/removed {@code TestRestTemplate} from its default test
 * autoconfiguration, and {@code MockMvc} exercises the same controller
 * dispatch path without needing a real HTTP client dependency.
 */
@SpringBootTest(properties = "agentic.workflows.directory=src/test/resources/workflows/webhook-ingress-test")
@AutoConfigureMockMvc
@Testcontainers
class WorkflowWebhookControllerTest {

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
    void ignoresWebhookWhenTriggerConditionDoesNotMatch() throws Exception {
        Map<String, Object> payload = Map.of(
                "ticketKey", "PROJ-1", "summary", "Add widget", "description", "Clear requirements.",
                "labels", List.of("not-ready"));

        mockMvc.perform(post("/webhooks/jira-plan-only-test/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    @Test
    void startsAndCompletesImmediatelyWhenThePlanHasNoOpenQuestions() throws Exception {
        Map<String, Object> payload = Map.of(
                "ticketKey", "PROJ-2", "summary", "Add widget", "description", "Clear, complete requirements.",
                "labels", List.of("ready-for-dev"));

        String json = mockMvc.perform(post("/webhooks/jira-plan-only-test/start")
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
    void startsPausesForSlackClarificationThenResumesIntoPostPlan() throws Exception {
        Map<String, Object> payload = Map.of(
                "ticketKey", "PROJ-3", "summary", "Add widget", "description", "",
                "labels", List.of("ready-for-dev"));

        String startJson = mockMvc.perform(post("/webhooks/jira-plan-only-test/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> startBody = objectMapper.readValue(startJson, Map.class);
        String threadId = (String) startBody.get("threadId");

        String correlationKey = awaitOnlyPendingCorrelationKey(threadId);

        List<AuditLogEntry> afterStart = auditLogRepository.findByThreadId(threadId);
        assertThat(afterStart).extracting(AuditLogEntry::nodeId).containsExactly("plan", "await-clarifications");

        Map<String, Object> slackReply = Map.of("slackAnswer", "It should show the widget count.");
        String resumeJson = mockMvc.perform(post("/webhooks/resume/" + correlationKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(slackReply)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> resumeBody = objectMapper.readValue(resumeJson, Map.class);
        assertThat(resumeBody).containsEntry("resumed", true);

        // await-clarifications now loops back into "plan" (rather than
        // straight to "post-plan") so PlanningAgent can redraft using the
        // team's answer — see PlanningAgent's Javadoc. The heuristic
        // fallback's answer contains neither "TBD" nor "?", so this second
        // "plan" pass resolves the question and routes on to "post-plan".
        List<AuditLogEntry> afterResume = awaitNodeIds(threadId, 4);
        assertThat(afterResume).extracting(AuditLogEntry::nodeId)
                .containsExactly("plan", "await-clarifications", "plan", "post-plan");
        AuditLogEntry postPlan = afterResume.get(3);
        assertThat((String) postPlan.payload().get("finalPlan")).contains("It should show the widget count.");
    }

    @Test
    void returnsNotFoundForUnknownWorkflow() throws Exception {
        mockMvc.perform(post("/webhooks/no-such-workflow/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of())))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsNotFoundForUnknownCorrelationKey() throws Exception {
        mockMvc.perform(post("/webhooks/resume/no-such-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of())))
                .andExpect(status().isNotFound());
    }

    /**
     * Execution now runs on the workflow engine's background executor (see
     * {@code WorkflowEngineService}), so audit entries/pending actions may
     * not exist yet the instant {@code /start} or {@code /resume} returns.
     */
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
