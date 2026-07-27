package io.github.manevpe.agentic.workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only, in-memory holder of every {@link WorkflowDefinition} loaded at
 * startup, keyed by workflow ID. This is what the graph engine
 * and webhook ingress query to find which workflow
 * a given trigger should start or resume.
 */
public final class WorkflowDefinitionRegistry {

    private final Map<String, WorkflowDefinition> byId;

    public WorkflowDefinitionRegistry(List<WorkflowDefinition> definitions) {
        this.byId = definitions.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(WorkflowDefinition::id, d -> d));
    }

    public Optional<WorkflowDefinition> find(String workflowId) {
        return Optional.ofNullable(byId.get(workflowId));
    }

    public List<WorkflowDefinition> all() {
        return List.copyOf(byId.values());
    }
}
