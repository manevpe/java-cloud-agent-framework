package io.github.manevpe.agentic.engine;

/** Outcome of a single node execution, used by the graph's routing edges. */
enum NodeExecutionStatus {
    CONTINUE,
    WAITING_FOR_EVENT,
    WAITING_FOR_APPROVAL,
    FAILED
}
