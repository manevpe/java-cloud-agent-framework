package io.github.manevpe.agentic.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "pending_action")
class PendingActionEntity {

    @Id
    private UUID id;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "correlation_key")
    private String correlationKey;

    @Convert(converter = JsonMapConverter.class)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    private io.github.manevpe.agentic.persistence.ApprovalStatus status;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected PendingActionEntity() {
        // JPA
    }

    PendingActionEntity(UUID id, String threadId, String nodeId, String actionType, String correlationKey,
                         Map<String, Object> payload, io.github.manevpe.agentic.persistence.ApprovalStatus status,
                         String decidedBy, Instant createdAt, Instant decidedAt) {
        this.id = id;
        this.threadId = threadId;
        this.nodeId = nodeId;
        this.actionType = actionType;
        this.correlationKey = correlationKey;
        this.payload = payload;
        this.status = status;
        this.decidedBy = decidedBy;
        this.createdAt = createdAt;
        this.decidedAt = decidedAt;
    }

    UUID getId() {
        return id;
    }

    String getThreadId() {
        return threadId;
    }

    String getNodeId() {
        return nodeId;
    }

    String getActionType() {
        return actionType;
    }

    String getCorrelationKey() {
        return correlationKey;
    }

    Map<String, Object> getPayload() {
        return payload;
    }

    io.github.manevpe.agentic.persistence.ApprovalStatus getStatus() {
        return status;
    }

    String getDecidedBy() {
        return decidedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }
}
