package io.github.manevpe.agentic.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_thread")
class WorkflowThreadEntity {

    @Id
    @Column(name = "thread_id")
    private String threadId;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowThreadEntity() {
        // JPA
    }

    WorkflowThreadEntity(String threadId, String workflowId, Instant createdAt) {
        this.threadId = threadId;
        this.workflowId = workflowId;
        this.createdAt = createdAt;
    }

    String getThreadId() {
        return threadId;
    }

    String getWorkflowId() {
        return workflowId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
