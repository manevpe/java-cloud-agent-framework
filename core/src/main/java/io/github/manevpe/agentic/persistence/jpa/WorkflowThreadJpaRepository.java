package io.github.manevpe.agentic.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

interface WorkflowThreadJpaRepository extends JpaRepository<WorkflowThreadEntity, String> {
}
