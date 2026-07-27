package io.github.manevpe.agentic.engine;

import io.github.manevpe.agentic.persistence.AuditLogEntry;
import io.github.manevpe.agentic.persistence.AuditLogRepository;
import io.github.manevpe.agentic.persistence.PendingActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end validation of the redesigned pause/resume model against a real
 * Postgres-backed {@code JpaCheckpointSaver}: a workflow starts, runs one
 * node, pauses at a node registered under LangGraph4j's native
 * {@code interruptsAfter} (see {@link WorkflowGraphFactory}), and later
 * resumes — via {@code GraphInput.resume(...)}, not a checkpoint restart —
 * continuing into (not re-running) the node that follows the pause point.
 */
@SpringBootTest(properties = "agentic.workflows.directory=src/test/resources/workflows/engine-test")
@Testcontainers
class WorkflowEngineServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WorkflowEngineService engineService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PendingActionRepository pendingActionRepository;

    @Test
    void startsPausesAndResumesAcrossTheAwaitNode() throws InterruptedException {
        String threadId = engineService.start("pause-resume-test", Map.of("task", "demo"));

        // Paused right after "await": the interrupt fires before "finalize" runs.
        // Execution runs on the background workflow executor (see
        // WorkflowEngineService), so poll rather than asserting immediately.
        awaitPendingByCorrelationKey("wait-await");
        List<AuditLogEntry> afterStart = auditLogRepository.findByThreadId(threadId);
        assertThat(afterStart).extracting(AuditLogEntry::nodeId).containsExactly("start", "await");
        assertThat(afterStart).noneMatch(e -> e.nodeId().equals("finalize"));

        engineService.resumeByCorrelationKey("wait-await", Map.of("answer", 42));

        // Resumed: "await" itself does not re-run, "finalize" now has, with the
        // resumed event payload merged into the state it saw.
        List<AuditLogEntry> afterResume = awaitNodeIds(threadId, 3);
        assertThat(pendingActionRepository.findPendingByCorrelationKey("wait-await")).isEmpty();
        assertThat(afterResume).extracting(AuditLogEntry::nodeId).containsExactly("start", "await", "finalize");

        AuditLogEntry finalizeEntry = afterResume.stream()
                .filter(e -> e.nodeId().equals("finalize"))
                .findFirst()
                .orElseThrow();
        assertThat(finalizeEntry.payload()).containsEntry("answer", 42);
        assertThat(finalizeEntry.payload()).containsEntry("finalized", true);
    }

    private void awaitPendingByCorrelationKey(String correlationKey) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (pendingActionRepository.findPendingByCorrelationKey(correlationKey).isPresent()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for pending action on correlation key " + correlationKey);
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
