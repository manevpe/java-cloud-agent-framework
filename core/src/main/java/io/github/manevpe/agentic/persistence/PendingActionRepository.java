package io.github.manevpe.agentic.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for the approval-gate / wait-for-event queue. See {@link PendingAction}. */
public interface PendingActionRepository {

    PendingAction save(PendingAction action);

    Optional<PendingAction> findById(UUID id);

    List<PendingAction> findPendingByThreadId(String threadId);

    List<PendingAction> findAllPending();

    /** Used to resume a paused thread when a correlated external event arrives. */
    Optional<PendingAction> findPendingByCorrelationKey(String correlationKey);
}
