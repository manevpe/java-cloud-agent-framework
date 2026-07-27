package io.github.manevpe.agentic.persistence;

import java.util.Optional;

/**
 * Tiny port mapping a running/paused thread ID (LangGraph4j's
 * {@code RunnableConfig.threadId()}) to the workflow definition it belongs
 * to. LangGraph4j checkpoints only store node/state, not which YAML
 * workflow produced them — this is what lets the engine rebuild/resume the
 * right compiled graph for a thread after a process restart.
 */
public interface WorkflowThreadRegistry {

    void register(String threadId, String workflowId);

    Optional<String> findWorkflowId(String threadId);
}
