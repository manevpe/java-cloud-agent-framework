package com.example.plugin;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;

/**
 * Template for a custom workflow-node agent, loaded from an external jar
 * via {@code ServiceLoader} rather than compiled into the main framework
 * repo. Copy this class (rename freely), then:
 *
 * <ol>
 *   <li>Change {@link #type()} to a unique id — this is what workflow YAML
 *       references via {@code node.agent}.</li>
 *   <li>Implement {@link #execute} with your own logic, reading input from
 *       {@code state} and returning one of {@link AgentResult}'s variants
 *       ({@code Continue} to proceed normally, {@code WaitForEvent}/{@code
 *       WaitForApproval} to pause until an external event/approval arrives,
 *       or {@code Failed} on unrecoverable error).</li>
 *   <li>List this class under {@code
 *       src/main/resources/META-INF/services/io.github.manevpe.agentic.agent.Agent}
 *       so {@code ServiceLoader} can find it.</li>
 * </ol>
 *
 * <p><b>Important:</b> the class must be {@code public} with a {@code
 * public} no-arg constructor (the implicit default constructor is fine, as
 * shown here) — {@code ServiceLoader} requires both, and a package-private
 * class or constructor fails at load time with {@code
 * ServiceConfigurationError}.
 */
public class ExampleAgent implements Agent {

    @Override
    public String type() {
        return "example-plugin-agent";
    }

    @Override
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        // Read whatever upstream nodes put into state, do your work, and
        // return the (possibly updated) state.
        WorkflowState updated = state.put("exampleAgentRan", true);
        return new AgentResult.Continue(updated);
    }
}
