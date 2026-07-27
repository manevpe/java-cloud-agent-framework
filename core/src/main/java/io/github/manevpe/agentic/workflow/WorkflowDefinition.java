package io.github.manevpe.agentic.workflow;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed, in-memory representation of a workflow YAML file. This is the
 * single reusable description consumed by the graph engine to
 * build an executable graph — adding a new workflow means authoring a new
 * YAML file, not writing new engine code.
 */
public record WorkflowDefinition(
        String id,
        TriggerDefinition trigger,
        Map<String, Object> stateSchemaRef,
        List<NodeDefinition> nodes,
        List<EdgeDefinition> edges
) {

    public WorkflowDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(trigger, "trigger");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public NodeDefinition node(String nodeId) {
        return nodes.stream()
                .filter(n -> n.id().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow '%s' has no node '%s'".formatted(id, nodeId)));
    }

    public List<EdgeDefinition> edgesFrom(String nodeId) {
        return edges.stream().filter(e -> e.from().equals(nodeId)).toList();
    }
}
