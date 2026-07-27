package io.github.manevpe.agentic.engine.support;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;
import org.springframework.stereotype.Component;

/**
 * Test-only agent that always pauses, waiting for an external event. The
 * correlation key is fixed as {@code "wait-" + node.id()} so tests can
 * predict it without inspecting persisted state.
 */
@Component
public class WaitOnceAgent implements Agent {

    @Override
    public String type() {
        return "test-wait-once-agent";
    }

    @Override
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        return new AgentResult.WaitForEvent(state, "wait-" + node.id());
    }
}
