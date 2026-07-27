package io.github.manevpe.agentic.persistence;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract every {@link AuditLogRepository} implementation must satisfy. */
public abstract class AuditLogRepositoryContractTest {

    protected abstract AuditLogRepository repository();

    @Test
    void appendsAndListsEntriesForAThreadInOrder() {
        String threadId = UUID.randomUUID().toString();
        AuditLogRepository repo = repository();

        repo.append(AuditLogEntry.of(threadId, "plan", "planning-agent", "PLAN_CREATED",
                Map.of("summary", "first pass"), ApprovalStatus.NOT_REQUIRED));
        repo.append(AuditLogEntry.of(threadId, "open-pr", "github-pr-agent", "PR_OPENED",
                Map.of("prUrl", "https://github.com/acme/repo/pull/1"), ApprovalStatus.APPROVED));

        var entries = repo.findByThreadId(threadId);

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(AuditLogEntry::actionType)
                .containsExactlyInAnyOrder("PLAN_CREATED", "PR_OPENED");
    }

    @Test
    void findByThreadIdIsScopedPerThread() {
        AuditLogRepository repo = repository();
        String threadA = UUID.randomUUID().toString();
        String threadB = UUID.randomUUID().toString();

        repo.append(AuditLogEntry.of(threadA, "plan", "planning-agent", "PLAN_CREATED",
                Map.of(), ApprovalStatus.NOT_REQUIRED));

        assertThat(repo.findByThreadId(threadB)).isEmpty();
        assertThat(repo.findByThreadId(threadA)).hasSize(1);
    }
}
