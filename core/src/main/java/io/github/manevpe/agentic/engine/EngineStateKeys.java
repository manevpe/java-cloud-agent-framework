package io.github.manevpe.agentic.engine;

/**
 * Reserved {@code AgentState} keys the graph engine uses for its own
 * bookkeeping (routing decisions, pause reasons). Agent implementations
 * must never read or write these directly — they only see/return the
 * business keys via {@link io.github.manevpe.agentic.workflow.WorkflowState}.
 */
final class EngineStateKeys {

    static final String STATUS = "_engine_status";
    static final String WAITING_CORRELATION_KEY = "_engine_waiting_correlation_key";
    static final String FAILURE_REASON = "_engine_failure_reason";
    static final String CURRENT_NODE_ID = "_engine_current_node_id";

    private EngineStateKeys() {
    }
}
