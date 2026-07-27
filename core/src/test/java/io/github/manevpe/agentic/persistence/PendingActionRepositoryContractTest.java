package io.github.manevpe.agentic.persistence;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract every {@link PendingActionRepository} implementation must satisfy. */
public abstract class PendingActionRepositoryContractTest {

    protected abstract PendingActionRepository repository();

    @Test
    void savesAndFindsAPendingActionById() {
        PendingActionRepository repo = repository();
        PendingAction proposed = repo.save(PendingAction.propose(
                UUID.randomUUID().toString(), "open-pr", "GITHUB_OPEN_PR", null, Map.of("branch", "feature/x")));

        PendingAction found = repo.findById(proposed.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(found.actionType()).isEqualTo("GITHUB_OPEN_PR");
    }

    @Test
    void approvingUpdatesStatusAndDecidedBy() {
        PendingActionRepository repo = repository();
        PendingAction proposed = repo.save(PendingAction.propose(
                UUID.randomUUID().toString(), "open-pr", "GITHUB_OPEN_PR", null, Map.of()));

        PendingAction approved = repo.save(proposed.approve("alice"));

        PendingAction reloaded = repo.findById(approved.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(reloaded.decidedBy()).isEqualTo("alice");
        assertThat(reloaded.decidedAt()).isNotNull();
    }

    @Test
    void findAllPendingExcludesDecidedActions() {
        PendingActionRepository repo = repository();
        PendingAction pending = repo.save(PendingAction.propose(
                UUID.randomUUID().toString(), "open-pr", "GITHUB_OPEN_PR", null, Map.of()));
        PendingAction decided = repo.save(PendingAction.propose(
                UUID.randomUUID().toString(), "post-plan", "JIRA_COMMENT", null, Map.of()));
        repo.save(decided.approve("bob"));

        assertThat(repo.findAllPending())
                .extracting(PendingAction::id)
                .contains(pending.id())
                .doesNotContain(decided.id());
    }

    @Test
    void findPendingByThreadIdIsScopedPerThread() {
        PendingActionRepository repo = repository();
        String threadId = UUID.randomUUID().toString();
        repo.save(PendingAction.propose(threadId, "open-pr", "GITHUB_OPEN_PR", null, Map.of()));
        repo.save(PendingAction.propose(UUID.randomUUID().toString(), "open-pr", "GITHUB_OPEN_PR", null, Map.of()));

        assertThat(repo.findPendingByThreadId(threadId)).hasSize(1);
    }

    @Test
    void findPendingByCorrelationKeyLocatesTheWaitingAction() {
        PendingActionRepository repo = repository();
        String correlationKey = "slack-thread:123.456";
        PendingAction proposed = repo.save(PendingAction.propose(
                UUID.randomUUID().toString(), "await-clarifications", "AWAIT_SLACK_REPLY",
                correlationKey, Map.of()));

        assertThat(repo.findPendingByCorrelationKey(correlationKey))
                .map(PendingAction::id)
                .contains(proposed.id());
        assertThat(repo.findPendingByCorrelationKey("no-such-key")).isEmpty();
    }
}
