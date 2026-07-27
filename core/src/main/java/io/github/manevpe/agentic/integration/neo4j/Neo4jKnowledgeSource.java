package io.github.manevpe.agentic.integration.neo4j;

import io.github.manevpe.agentic.integration.KnowledgeSource;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Real {@link KnowledgeSource} backed by one Neo4j database within a
 * shared {@link Driver}'s instance. Built per node-configured {@code
 * database} by {@code NodeKnowledgeSourceResolver} — not a Spring bean
 * itself, since which database(s) are queried is a per-workflow-node
 * choice (see its {@code knowledgeSources: [{type: neo4j, database:
 * ...}]} config), not a single global setting.
 *
 * <p>Schema (MVP): {@code (:KnowledgeItem {text: string, keywords: [string]})}
 * nodes. A query is matched against a node's {@code keywords} by simple
 * case-insensitive substring containment on each whitespace-separated term
 * in the input — deliberately simple; swap for a full-text
 * or vector index later without changing the {@link KnowledgeSource}
 * contract callers rely on.
 */
public class Neo4jKnowledgeSource implements KnowledgeSource {

    private static final String CYPHER = """
            UNWIND $terms AS term
            MATCH (k:KnowledgeItem)
            WHERE any(kw IN k.keywords WHERE toLower(kw) CONTAINS toLower(term))
            RETURN DISTINCT k.text AS text
            LIMIT $limit
            """;

    private final Driver driver;
    private final String database;
    private final int resultLimit;

    public Neo4jKnowledgeSource(Driver driver, String database, int resultLimit) {
        this.driver = driver;
        this.database = database;
        this.resultLimit = resultLimit;
    }

    @Override
    public List<String> query(String query) {
        List<String> terms = Arrays.stream(query.trim().split("\\s+"))
                .filter(term -> !term.isBlank())
                .toList();
        if (terms.isEmpty()) {
            return List.of();
        }

        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            return session.executeRead(tx -> tx.run(CYPHER, Map.of("terms", terms, "limit", resultLimit))
                    .list(record -> record.get("text").asString()));
        }
    }
}
