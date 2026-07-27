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
@Table(name = "audit_log")
class AuditLogEntityJpa {

    @Id
    private UUID id;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Convert(converter = JsonMapConverter.class)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 64)
    private io.github.manevpe.agentic.persistence.ApprovalStatus approvalStatus;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLogEntityJpa() {
        // JPA
    }

    AuditLogEntityJpa(UUID id, String threadId, String nodeId, String actor, String actionType,
                       Map<String, Object> payload, io.github.manevpe.agentic.persistence.ApprovalStatus approvalStatus,
                       Instant occurredAt) {
        this.id = id;
        this.threadId = threadId;
        this.nodeId = nodeId;
        this.actor = actor;
        this.actionType = actionType;
        this.payload = payload;
        this.approvalStatus = approvalStatus;
        this.occurredAt = occurredAt;
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

    String getActor() {
        return actor;
    }

    String getActionType() {
        return actionType;
    }

    Map<String, Object> getPayload() {
        return payload;
    }

    io.github.manevpe.agentic.persistence.ApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
