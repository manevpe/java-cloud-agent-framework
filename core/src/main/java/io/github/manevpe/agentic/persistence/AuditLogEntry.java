package io.github.manevpe.agentic.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable record of a single action taken (or attempted) by the system —
 * an agent decision, an external side effect, or a human approval decision.
 * Append-only: adapters should implement this as an insert-only table.
 *
 * <p>{@code threadId} correlates entries with the LangGraph4j checkpoint
 * thread (see {@code JpaCheckpointSaver}) that represents the running
 * workflow instance.
 */
public record AuditLogEntry(
        UUID id,
        String threadId,
        String nodeId,
        String actor,
        String actionType,
        Map<String, Object> payload,
        ApprovalStatus approvalStatus,
        Instant occurredAt
) {
    public static AuditLogEntry of(
            String threadId, String nodeId, String actor, String actionType,
            Map<String, Object> payload, ApprovalStatus approvalStatus) {
        return new AuditLogEntry(
                UUID.randomUUID(), threadId, nodeId, actor, actionType,
                Map.copyOf(payload), approvalStatus, Instant.now());
    }
}
