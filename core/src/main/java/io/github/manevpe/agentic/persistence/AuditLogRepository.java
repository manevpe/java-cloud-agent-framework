package io.github.manevpe.agentic.persistence;

import java.util.List;

/** Append-only audit trail port. See {@link AuditLogEntry}. */
public interface AuditLogRepository {

    AuditLogEntry append(AuditLogEntry entry);

    List<AuditLogEntry> findByThreadId(String threadId);
}
