package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code agentic.knowledge.neo4j.*} properties: the shared Neo4j
 * connection (one instance can host several databases). Only consulted
 * when {@code enabled=true}, in which case a {@link Driver} bean exists
 * (see {@code Neo4jAutoConfiguration}) and {@code
 * NodeKnowledgeSourceResolver} can build a {@code Neo4jKnowledgeSource}
 * against whichever {@code database} a workflow node's own {@code
 * knowledgeSources: [{type: neo4j, database: ...}]} config names — the
 * database itself is a per-node choice, not part of this shared
 * connection config.
 *
 * @param enabled  whether to stand up a real Neo4j driver at all (default
 *                 {@code false} — safe without a graph instance available)
 * @param uri      Bolt URI (e.g. {@code bolt://localhost:7687})
 * @param username Neo4j username
 * @param password Neo4j password
 * @param resultLimit maximum number of knowledge snippets returned per query
 */
@ConfigurationProperties(prefix = "agentic.knowledge.neo4j")
public record Neo4jProperties(
        boolean enabled,
        String uri,
        String username,
        String password,
        int resultLimit
) {
    public Neo4jProperties {
        uri = (uri == null || uri.isBlank()) ? "bolt://localhost:7687" : uri;
        username = (username == null || username.isBlank()) ? "neo4j" : username;
        resultLimit = resultLimit <= 0 ? 20 : resultLimit;
    }
}
