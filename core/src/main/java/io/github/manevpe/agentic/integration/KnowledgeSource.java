package io.github.manevpe.agentic.integration;

import java.util.List;

/**
 * Port to whatever domain-knowledge source the planning agent consults
 * (Neo4j knowledge graph, Backstage catalog, context files, ...). Built-in
 * implementations are {@code Neo4jKnowledgeSource} (graph-backed) and
 * {@code DirectoryKnowledgeSource} (plain context files).
 */
public interface KnowledgeSource {

    /** Returns relevant knowledge snippets for the given free-text query. */
    List<String> query(String query);
}
