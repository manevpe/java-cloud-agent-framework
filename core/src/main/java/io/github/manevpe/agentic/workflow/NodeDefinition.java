package io.github.manevpe.agentic.workflow;

import java.util.Map;
import java.util.Objects;

/**
 * A single node in a workflow graph. {@code agent} is the bean name/type ID
 * of a registered {@code io.github.manevpe.agentic.agent.Agent}
 * implementation; {@code config} is opaque, agent-specific configuration
 * (e.g. which LLM profile to use, which knowledge sources to query).
 */
public record NodeDefinition(
        String id,
        String agent,
        Map<String, Object> config,
        boolean requiresApproval,
        TriggerDefinition resumeTrigger
) {

    public NodeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agent, "agent");
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
