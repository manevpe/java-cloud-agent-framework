package io.github.manevpe.agentic.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An external action (Jira comment, Slack post, git push, PR comment, ...)
 * proposed by an agent but not yet executed, awaiting a human decision — or
 * an external event (Slack reply, GitHub comment) an agent is waiting on
 * before it can resume. This is the queue backing the approval-gate
 * guardrail and the "waiting for event" resume mechanism described in the
 * design: every side effect visible outside the system, and every pause
 * point, passes through here.
 *
 * <p>{@code correlationKey} is the value an inbound webhook/event carries
 * (e.g. a Slack thread ID) used to look up which paused thread/node to
 * resume; it is {@code null} for approval-gate entries that are resolved
 * via the approval API rather than a correlated external event. A
 * DB-portable unique index enforces at most one row per non-null
 * correlation key (see the {@code 001-init-schema} changelog) —
 * {@link #approve} / {@link #reject} clear it to {@code null} so resolved
 * rows never collide with a later pending action reusing the same key.
 */
public record PendingAction(
        UUID id,
        String threadId,
        String nodeId,
        String actionType,
        String correlationKey,
        Map<String, Object> payload,
        ApprovalStatus status,
        String decidedBy,
        Instant createdAt,
        Instant decidedAt
) {

    public static PendingAction propose(
            String threadId, String nodeId, String actionType, String correlationKey, Map<String, Object> payload) {
        return new PendingAction(
                UUID.randomUUID(), threadId, nodeId, actionType, correlationKey,
                Map.copyOf(payload), ApprovalStatus.PENDING, null, Instant.now(), null);
    }

    public PendingAction approve(String decidedBy) {
        return new PendingAction(
                id, threadId, nodeId, actionType, null, payload,
                ApprovalStatus.APPROVED, decidedBy, createdAt, Instant.now());
    }

    public PendingAction reject(String decidedBy) {
        return new PendingAction(
                id, threadId, nodeId, actionType, null, payload,
                ApprovalStatus.REJECTED, decidedBy, createdAt, Instant.now());
    }
}
