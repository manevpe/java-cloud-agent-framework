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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * End-to-end validation of the PR feedback loop: after {@code implement}
 * opens the PR, {@code pr-comment-gate} pauses waiting for the next
 * GitHub event on the PR. This test drives the loop through a full cycle:
 * a review with a comment requesting a change (handled inline by {@code
 * CodingAgent}'s {@code respond-to-review} mode, which posts a reply and
 * pushes an amending commit itself — no separate approval gate, see
 * ADR-0004), a second review needing no change, and finally the PR being
 * merged (which ends the loop instead of waiting for a 3rd/4th cycle,
 * well under the workflow's {@code maxIterations: 3}).
 *
 * <p>{@code CodingAgent} now runs its own LLM tool-calling loop
 * synchronously, in-process (see {@code CodingAndPrFlowTest}'s Javadoc)
 * rather than dispatching an opaque Kubernetes {@code Job} and waiting for
 * a callback — {@link FakeLlmClient} substitutes for a real model here
 * too, reproducing the original keyword-matching heuristic for whether a
 * review comment needs a code change.
 */
@SpringBootTest(properties = {
        "agentic.workflows.directory=src/test/resources/workflows/pr-feedback-loop-test",
        "agentic.llm.enabled=true",
        "agentic.llm.google-genai.project-id=fake-project-for-test"
})
@AutoConfigureMockMvc
@Import(FakeLlmClient.Config.class)
@Testcontainers
class PrFeedbackLoopFlowTest {

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
    void reviewWithChangeRequestThenCleanReviewThenMergeEndsTheLoop() throws Exception {
        Map<String, Object> payload = Map.of(
                "ticketKey", "PROJ-20", "summary", "Add widget", "description", "Clear, complete requirements.",
                "labels", List.of("ready-for-dev"));

        String startJson = mockMvc.perform(post("/webhooks/jira-to-pr-feedback-test/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String threadId = (String) objectMapper.readValue(startJson, Map.class).get("threadId");
        assertThat(threadId).isNotBlank();

        // implement runs its tool-calling loop synchronously, opens the PR, and pauses at pr-feedback-loop.
        assertThat(awaitNodeIds(threadId, 4))
                .containsExactly("plan", "post-plan", "implement", "pr-feedback-loop");

        // --- Cycle 1: a review with a comment requesting a change. ---
        String prCommentKey = awaitOnlyPendingCorrelationKey(threadId);
        assertThat(prCommentKey).startsWith("pr-comment:");
        resume(prCommentKey, Map.of(
                "prEventType", "review_submitted",
                "reviewComments", List.of("Could you please fix the null check on line 42?")));

        assertThat(awaitNodeIds(threadId, 6)).containsExactly(
                "plan", "post-plan", "implement", "pr-feedback-loop",
                "respond-to-pr-comment", "pr-feedback-loop");

        // --- Cycle 2: a review needing no change. ---
        String prCommentKey2 = awaitOnlyPendingCorrelationKey(threadId);
        resume(prCommentKey2, Map.of(
                "prEventType", "review_submitted",
                "reviewComments", List.of("Looks good, nice work!")));

        assertThat(awaitNodeIds(threadId, 8)).containsExactly(
                "plan", "post-plan", "implement", "pr-feedback-loop",
                "respond-to-pr-comment", "pr-feedback-loop",
                "respond-to-pr-comment", "pr-feedback-loop");

        // --- Cycle 3: the PR is merged; the loop ends instead of pausing again. ---
        String prCommentKey3 = awaitOnlyPendingCorrelationKey(threadId);
        resume(prCommentKey3, Map.of("prEventType", "merged"));

        List<String> finalNodes = awaitNodeIds(threadId, 9);
        assertThat(finalNodes).containsExactly(
                "plan", "post-plan", "implement", "pr-feedback-loop",
                "respond-to-pr-comment", "pr-feedback-loop",
                "respond-to-pr-comment", "pr-feedback-loop",
                "respond-to-pr-comment");
        assertThat(awaitPendingEmpty(threadId)).isTrue();
    }

    private void resume(String correlationKey, Map<String, Object> eventPayload) throws Exception {
        mockMvc.perform(post("/webhooks/resume/" + correlationKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(eventPayload)))
                .andExpect(status().isOk());
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

    private boolean awaitPendingEmpty(String threadId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (pendingActionRepository.findPendingByThreadId(threadId).isEmpty()) {
                return true;
            }
            Thread.sleep(100);
        }
        return pendingActionRepository.findPendingByThreadId(threadId).isEmpty();
    }

    private List<String> awaitNodeIds(String threadId, int minSize) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<String> nodeIds;
        do {
            nodeIds = auditLogRepository.findByThreadId(threadId).stream().map(AuditLogEntry::nodeId).toList();
            if (nodeIds.size() >= minSize) {
                return nodeIds;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return nodeIds;
    }
}
