package io.github.manevpe.agentic.integration.knowledge;

import io.github.manevpe.agentic.config.Neo4jProperties;
import io.github.manevpe.agentic.integration.KnowledgeSource;
import io.github.manevpe.agentic.integration.directory.DirectoryKnowledgeSource;
import io.github.manevpe.agentic.integration.neo4j.Neo4jKnowledgeSource;
import org.neo4j.driver.Driver;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a workflow node's own {@code knowledgeSources: [...]} config —
 * a list of source specs, each an explicit {@code type} plus type-specific
 * fields — into actual {@link KnowledgeSource}s and queries them, so
 * different nodes/agents in the same workflow can each read from entirely
 * different local directories (or Neo4j databases), not a shared globally
 * pre-registered set. See {@code jira-to-pr.yaml} for example config:
 *
 * <pre>{@code
 * knowledgeSources:
 *   - type: directory
 *     path: ./knowledge/backstage-catalog
 *   - type: directory
 *     path: ./knowledge/context-files
 *   - type: neo4j
 *     database: domain-knowledge
 * }</pre>
 *
 * <p>{@code directory} sources are cached by their resolved absolute path
 * and {@code neo4j} sources by database name, both across every node/call
 * in the app's lifetime, since re-reading unchanged files or re-resolving
 * the same database on every agent round would be wasteful — directory
 * content is read once at first use (see {@link DirectoryKnowledgeSource}).
 */
public class NodeKnowledgeSourceResolver {

    private final Driver neo4jDriver;
    private final Neo4jProperties neo4jProperties;
    private final Map<Path, KnowledgeSource> directorySourcesByPath = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeSource> neo4jSourcesByDatabase = new ConcurrentHashMap<>();

    public NodeKnowledgeSourceResolver(Driver neo4jDriver, Neo4jProperties neo4jProperties) {
        this.neo4jDriver = neo4jDriver;
        this.neo4jProperties = neo4jProperties;
    }

    /**
     * Resolves and queries every source spec in {@code sourceConfigs},
     * concatenating their results — an empty list (the default when a
     * node omits {@code knowledgeSources}) simply returns no knowledge
     * context, by design; there is no implicit "all sources" fallback.
     */
    public List<String> resolve(List<Map<String, Object>> sourceConfigs, String query) {
        return sourceConfigs.stream()
                .flatMap(config -> sourceFor(config).query(query).stream())
                .toList();
    }

    private KnowledgeSource sourceFor(Map<String, Object> config) {
        String type = requireString(config, "type");
        return switch (type) {
            case "directory" -> directorySourceFor(requireString(config, "path"));
            case "neo4j" -> neo4jSourceFor(requireString(config, "database"));
            default -> throw new IllegalArgumentException(
                    "Unknown knowledgeSources entry type '" + type + "' (expected 'directory' or 'neo4j'): " + config);
        };
    }

    private KnowledgeSource directorySourceFor(String path) {
        Path resolved = Path.of(path).toAbsolutePath().normalize();
        return directorySourcesByPath.computeIfAbsent(resolved, DirectoryKnowledgeSource::new);
    }

    private KnowledgeSource neo4jSourceFor(String database) {
        if (neo4jDriver == null) {
            throw new IllegalStateException(
                    "A node's knowledgeSources config references a neo4j source (database '" + database
                            + "'), but agentic.knowledge.neo4j.enabled is not true");
        }
        return neo4jSourcesByDatabase.computeIfAbsent(database,
                db -> new Neo4jKnowledgeSource(neo4jDriver, db, neo4jProperties.resultLimit()));
    }

    private static String requireString(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("knowledgeSources entry missing required '" + key + "': " + config);
        }
        return String.valueOf(value);
    }
}
