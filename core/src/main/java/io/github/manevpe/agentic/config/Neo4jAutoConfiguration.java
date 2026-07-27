package io.github.manevpe.agentic.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the real Neo4j {@link Driver} bean backing {@code neo4j}-typed
 * entries in a workflow node's {@code knowledgeSources} config (see {@code
 * NodeKnowledgeSourceResolver}) — the only piece of the knowledge-graph
 * integration that imports the Neo4j driver directly. {@code
 * Neo4jKnowledgeSource} depends on this bean. Uses the plain Neo4j Java
 * driver directly (not Spring Data Neo4j), mirroring this project's
 * hand-rolled-port style for its other integrations ({@code
 * GitHubClient}/{@code JiraClient}/{@code SlackClient}/{@code
 * SandboxJobDispatcher}). {@link Neo4jProperties} itself is always
 * registered by {@code KnowledgeSourceAutoConfiguration} regardless of
 * this bean's activation, since the resolver needs it to fail clearly
 * when Neo4j is disabled.
 *
 * <p>Active only when {@code agentic.knowledge.neo4j.enabled=true} — e.g.
 * when a real Neo4j instance is reachable.
 */
@Configuration
@ConditionalOnProperty(prefix = "agentic.knowledge.neo4j", name = "enabled", havingValue = "true")
public class Neo4jAutoConfiguration {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(Neo4jProperties properties) {
        return GraphDatabase.driver(properties.uri(), AuthTokens.basic(properties.username(), properties.password()));
    }
}
