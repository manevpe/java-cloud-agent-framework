package io.github.manevpe.agentic.engine.support;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;
import org.springframework.stereotype.Component;

/**
 * Test-only agent that always continues, optionally stamping a marker key
 * (from {@code node.config().get("marks")}) so tests can assert it ran.
 */
@Component
public class EchoAgent implements Agent {

    @Override
    public String type() {
        return "test-echo-agent";
    }

    @Override
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        Object marker = node.config().get("marks");
        if (marker != null) {
            state.put((String) marker, true);
        }
        return new AgentResult.Continue(state);
    }
}
