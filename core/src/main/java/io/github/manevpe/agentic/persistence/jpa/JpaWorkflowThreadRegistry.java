package io.github.manevpe.agentic.persistence.jpa;

import io.github.manevpe.agentic.persistence.WorkflowThreadRegistry;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
class JpaWorkflowThreadRegistry implements WorkflowThreadRegistry {

    private final WorkflowThreadJpaRepository jpaRepository;

    JpaWorkflowThreadRegistry(WorkflowThreadJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void register(String threadId, String workflowId) {
        if (jpaRepository.existsById(threadId)) {
            return;
        }
        jpaRepository.save(new WorkflowThreadEntity(threadId, workflowId, Instant.now()));
    }

    @Override
    public Optional<String> findWorkflowId(String threadId) {
        return jpaRepository.findById(threadId).map(WorkflowThreadEntity::getWorkflowId);
    }
}
