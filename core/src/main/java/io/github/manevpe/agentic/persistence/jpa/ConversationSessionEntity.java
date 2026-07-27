package io.github.manevpe.agentic.persistence.jpa;

import io.github.manevpe.agentic.conversation.ConversationStatus;
import io.github.manevpe.agentic.conversation.ConversationTurn;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "conversation_session")
class ConversationSessionEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_thread_id", nullable = false)
    private String workflowThreadId;

    @Column(name = "agent_type", nullable = false)
    private String agentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ConversationStatus status;

    @Column(name = "correlation_key")
    private String correlationKey;

    @Convert(converter = ConversationTurnListConverter.class)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "turns", nullable = false, columnDefinition = "json")
    private List<ConversationTurn> turns;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversationSessionEntity() {
        // JPA
    }

    ConversationSessionEntity(UUID id, String workflowThreadId, String agentType, ConversationStatus status,
                               String correlationKey, List<ConversationTurn> turns, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.workflowThreadId = workflowThreadId;
        this.agentType = agentType;
        this.status = status;
        this.correlationKey = correlationKey;
        this.turns = turns;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    String getWorkflowThreadId() {
        return workflowThreadId;
    }

    String getAgentType() {
        return agentType;
    }

    ConversationStatus getStatus() {
        return status;
    }

    String getCorrelationKey() {
        return correlationKey;
    }

    List<ConversationTurn> getTurns() {
        return turns;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
