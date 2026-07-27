package io.github.manevpe.agentic.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Map;

/**
 * A single LangGraph4j {@code Checkpoint} row: one snapshot of a workflow
 * thread's state after a node finished executing. {@code sequence} is an
 * application-assigned monotonic value (not a DB identity/serial column, to
 * stay portable across databases) used to order checkpoints within a
 * thread newest-first, matching the ordering LangGraph4j's
 * {@code AbstractCheckpointSaver} expects.
 */
@Entity
@Table(name = "workflow_checkpoint")
class CheckpointEntity {

    @Id
    private String id;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "next_node_id", nullable = false)
    private String nextNodeId;

    @Convert(converter = JsonMapConverter.class)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "state", nullable = false, columnDefinition = "json")
    private Map<String, Object> state;

    @Column(name = "sequence", nullable = false)
    private long sequence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CheckpointEntity() {
        // JPA
    }

    CheckpointEntity(String id, String threadId, String nodeId, String nextNodeId,
                      Map<String, Object> state, long sequence, Instant createdAt) {
        this.id = id;
        this.threadId = threadId;
        this.nodeId = nodeId;
        this.nextNodeId = nextNodeId;
        this.state = state;
        this.sequence = sequence;
        this.createdAt = createdAt;
    }

    String getId() {
        return id;
    }

    String getThreadId() {
        return threadId;
    }

    String getNodeId() {
        return nodeId;
    }

    String getNextNodeId() {
        return nextNodeId;
    }

    Map<String, Object> getState() {
        return state;
    }

    long getSequence() {
        return sequence;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
