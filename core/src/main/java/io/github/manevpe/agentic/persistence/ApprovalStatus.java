package io.github.manevpe.agentic.persistence;

/** Approval state of an action recorded in the audit log / pending-action queue. */
public enum ApprovalStatus {
    NOT_REQUIRED,
    PENDING,
    APPROVED,
    REJECTED
}
