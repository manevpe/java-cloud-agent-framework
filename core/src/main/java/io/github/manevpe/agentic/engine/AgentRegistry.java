package io.github.manevpe.agentic.engine;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.config.AgentProperties;
import io.github.manevpe.agentic.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the {@code agent} name referenced by a workflow YAML node to the
 * registered {@link Agent} bean. Adding a new capability to the framework is
 * exactly: implement {@link Agent}, register it as a Spring bean, and
 * reference its {@link Agent#type()} from any workflow YAML — no engine
 * changes required.
 *
 * <p>Merges every built-in {@link Agent} Spring bean with every {@link
 * Agent} discovered from external plugin jars by {@link PluginManager} —
 * a workflow YAML can reference either kind identically, since
 * both are resolved from the same flat {@code type() -> Agent} map.
 * Duplicate types (built-in vs. plugin, or plugin vs. plugin) fail fast at
 * startup rather than silently letting one shadow the other.
 *
 * <p>{@link AgentProperties#disabledTypes()} is applied last, after
 * merging: an operator can disable a built-in or a plugin-provided agent
 * type by name without removing code or a jar, e.g. to turn off a
 * capability that isn't relevant to their team's workflow.
 */
@Component
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final Map<String, Agent> agentsByType;

    public AgentRegistry(List<Agent> springAgents, PluginManager pluginManager, AgentProperties properties) {
        Map<String, Agent> merged = new HashMap<>();
        for (Agent agent : springAgents) {
            merged.put(agent.type(), agent);
        }
        for (Agent agent : pluginManager.loadAgents()) {
            if (merged.containsKey(agent.type())) {
                throw new IllegalStateException(
                        "Plugin agent type '%s' collides with an already-registered agent of the same type"
                                .formatted(agent.type()));
            }
            merged.put(agent.type(), agent);
        }
        for (String disabledType : properties.disabledTypes()) {
            if (merged.remove(disabledType) != null) {
                log.info("Agent type '{}' disabled via agentic.agents.disabled-types", disabledType);
            }
        }
        this.agentsByType = Map.copyOf(merged);
    }

    public Agent resolve(String type) {
        Agent agent = agentsByType.get(type);
        if (agent == null) {
            throw new IllegalStateException(
                    "No agent registered for type '%s'. Registered types: %s"
                            .formatted(type, agentsByType.keySet()));
        }
        return agent;
    }
}
