package io.github.manevpe.agentic.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface AuditLogJpaRepository extends JpaRepository<AuditLogEntityJpa, UUID> {

    List<AuditLogEntityJpa> findByThreadId(String threadId);
}
