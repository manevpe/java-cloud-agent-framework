package io.github.manevpe.agentic.config;

import io.github.manevpe.agentic.integration.knowledge.NodeKnowledgeSourceResolver;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link NodeKnowledgeSourceResolver} every workflow node's own
 * {@code knowledgeSources: [...]} config is resolved against — see its
 * Javadoc for the per-node {@code directory}/{@code neo4j} source spec
 * format. The optional Neo4j {@link Driver} bean (see {@code
 * Neo4jAutoConfiguration}) is injected only if {@code
 * agentic.knowledge.neo4j.enabled=true}; nodes referencing a {@code neo4j}
 * source while it's disabled get a clear error at resolution time instead
 * of silently returning no results.
 */
@Configuration
@EnableConfigurationProperties(Neo4jProperties.class)
public class KnowledgeSourceAutoConfiguration {

    @Bean
    public NodeKnowledgeSourceResolver nodeKnowledgeSourceResolver(
            ObjectProvider<Driver> neo4jDriver, Neo4jProperties neo4jProperties) {
        return new NodeKnowledgeSourceResolver(neo4jDriver.getIfAvailable(), neo4jProperties);
    }
}
