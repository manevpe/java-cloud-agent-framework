package io.github.manevpe.agentic.config;

import io.github.manevpe.agentic.workflow.EdgeDefinition;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.TriggerDefinition;
import io.github.manevpe.agentic.workflow.WorkflowDefinition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses workflow definition YAML files into {@link WorkflowDefinition}
 * instances. This is the single place that understands the on-disk YAML
 * schema; the engine only ever consumes the parsed
 * {@code WorkflowDefinition} object graph.
 *
 * <p>Deliberately dependency-light (SnakeYAML's {@link SafeConstructor} only)
 * so {@code core} stays free of Spring/Jackson-YAML coupling and can be
 * reused standalone (e.g. in CLI tooling to validate workflow files in CI).
 */
public final class WorkflowConfigLoader {

    private final Yaml yaml;

    public WorkflowConfigLoader() {
        this.yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    public WorkflowDefinition load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in, file.toString());
        } catch (IOException e) {
            throw new WorkflowConfigException("Failed to read workflow file: " + file, e);
        }
    }

    public WorkflowDefinition load(InputStream in, String sourceDescription) {
        Object root = yaml.load(in);
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new WorkflowConfigException("Workflow file is not a YAML mapping: " + sourceDescription);
        }
        Object workflowNode = rootMap.get("workflow");
        if (!(workflowNode instanceof Map<?, ?> workflow)) {
            throw new WorkflowConfigException(
                    "Workflow file must have a top-level 'workflow:' key: " + sourceDescription);
        }
        return parseWorkflow(asStringKeyedMap(workflow), sourceDescription);
    }

    /** Loads every {@code *.yaml}/{@code *.yml} file in a directory (non-recursive). */
    public List<WorkflowDefinition> loadAll(Path directory) {
        List<WorkflowDefinition> definitions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.{yaml,yml}")) {
            for (Path file : stream) {
                definitions.add(load(file));
            }
        } catch (IOException e) {
            throw new WorkflowConfigException("Failed to scan workflow directory: " + directory, e);
        }
        return definitions;
    }

    private WorkflowDefinition parseWorkflow(Map<String, Object> workflow, String source) {
        String id = requireString(workflow, "id", source);
        TriggerDefinition trigger = parseTrigger(requireMap(workflow, "trigger", source), source);

        List<NodeDefinition> nodes = new ArrayList<>();
        for (Object rawNode : requireList(workflow, "nodes", source)) {
            nodes.add(parseNode(asStringKeyedMap(rawNode), source));
        }

        List<EdgeDefinition> edges = new ArrayList<>();
        Object rawEdges = workflow.get("edges");
        if (rawEdges instanceof List<?> list) {
            for (Object rawEdge : list) {
                edges.add(parseEdge(asStringKeyedMap(rawEdge), source));
            }
        }

        WorkflowDefinition definition = new WorkflowDefinition(id, trigger, Map.of(), nodes, edges);
        validateReferentialIntegrity(definition, source);
        return definition;
    }

    private NodeDefinition parseNode(Map<String, Object> node, String source) {
        String id = requireString(node, "id", source);
        String agent = requireString(node, "agent", source);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        boolean requiresApproval = Boolean.TRUE.equals(node.get("requiresApproval"));
        TriggerDefinition resumeTrigger = node.get("resumeTrigger") instanceof Map<?, ?> rt
                ? parseTrigger(asStringKeyedMap(rt), source)
                : null;
        return new NodeDefinition(id, agent, config, requiresApproval, resumeTrigger);
    }

    private EdgeDefinition parseEdge(Map<String, Object> edge, String source) {
        String from = requireString(edge, "from", source);
        String to = requireString(edge, "to", source);
        String condition = (String) edge.get("condition");
        return new EdgeDefinition(from, to, condition);
    }

    private TriggerDefinition parseTrigger(Map<String, Object> trigger, String source) {
        String type = requireString(trigger, "type", source);
        String triggerSource = requireString(trigger, "source", source);
        String condition = (String) trigger.get("condition");
        String event = (String) trigger.get("event");
        return new TriggerDefinition(type, triggerSource, condition, event);
    }

    private void validateReferentialIntegrity(WorkflowDefinition definition, String source) {
        for (EdgeDefinition edge : definition.edges()) {
            try {
                definition.node(edge.from());
                definition.node(edge.to());
            } catch (IllegalArgumentException e) {
                throw new WorkflowConfigException(
                        "%s: %s".formatted(source, e.getMessage()), e);
            }
        }
    }

    private static Map<String, Object> asStringKeyedMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new WorkflowConfigException("Expected a YAML mapping but found: " + raw);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static String requireString(Map<String, Object> map, String key, String source) {
        Object value = map.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new WorkflowConfigException("Missing required field '%s' in %s".formatted(key, source));
        }
        return s;
    }

    private static Map<String, Object> requireMap(Map<String, Object> map, String key, String source) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new WorkflowConfigException("Missing required mapping '%s' in %s".formatted(key, source));
        }
        return asStringKeyedMap(value);
    }

    private static List<?> requireList(Map<String, Object> map, String key, String source) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new WorkflowConfigException("Missing required non-empty list '%s' in %s".formatted(key, source));
        }
        return list;
    }
}
