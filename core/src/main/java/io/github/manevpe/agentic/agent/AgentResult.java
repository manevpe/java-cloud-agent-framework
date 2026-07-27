package io.github.manevpe.agentic.agent;

import io.github.manevpe.agentic.workflow.WorkflowState;

/**
 * Outcome of a single agent execution: the (possibly updated) state, and how
 * the engine should proceed next.
 */
public sealed interface AgentResult {

    WorkflowState state();

    /** Node finished normally; the engine evaluates outgoing edges to continue. */
    record Continue(WorkflowState state) implements AgentResult {}

    /**
     * Node needs to pause until an external event arrives (e.g. Slack reply,
     * GitHub PR comment). {@code correlationKey} identifies which future
     * inbound event resumes this instance.
     */
    record WaitForEvent(WorkflowState state, String correlationKey) implements AgentResult {}

    /**
     * Node produced an external action that must be approved by a human
     * before it is executed.
     */
    record WaitForApproval(WorkflowState state, String correlationKey) implements AgentResult {}

    /** Node failed unrecoverably; the engine marks the instance FAILED. */
    record Failed(WorkflowState state, String reason) implements AgentResult {}
}
