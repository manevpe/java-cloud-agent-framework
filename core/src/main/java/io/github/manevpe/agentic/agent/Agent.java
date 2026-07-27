package io.github.manevpe.agentic.agent;

import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;

import java.util.List;

/**
 * A single unit of work executed at one node of a workflow graph. Agent
 * implementations register themselves under a type ID (e.g.
 * {@code "planning-agent"}) that workflow YAML files reference by name —
 * this is the core extension point of the framework: new capabilities are
 * added by implementing this interface once, then reused across any number
 * of workflow definitions.
 */
public interface Agent {

    /**
     * The type ID this agent is registered under, referenced from workflow
     * YAML as {@code node.agent}. Must be unique across all registered agents.
     */
    String type();

    /**
     * The {@link io.github.manevpe.agentic.tool.ToolBundle} names this
     * agent cannot function without, resolved automatically from {@code
     * PluginContext#toolRegistry()} — a missing name fails fast at
     * startup rather than silently leaving the agent without a tool it
     * needs. Defaults to none, for agents that don't call an LLM or
     * construct their own tools directly. See ADR-0008.
     */
    default List<String> requiredTools() {
        return List.of();
    }

    /**
     * Executes this agent's logic for the given node/state.
     *
     * <p>External side effects (Jira comments, Slack posts, git pushes,
     * PR creation, ...) execute directly against real integration ports —
     * see ADR-0004 for which actions auto-execute versus require a
     * human approval gate (via {@link AgentResult.WaitForApproval}) before
     * the effect fires.
     */
    AgentResult execute(NodeDefinition node, WorkflowState state);
}
