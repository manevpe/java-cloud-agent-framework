package io.github.manevpe.agentic.integration.knowledge;

import io.github.manevpe.agentic.config.Neo4jProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link NodeKnowledgeSourceResolver} resolves each per-node
 * {@code knowledgeSources} entry by its own explicit {@code type}, so
 * different nodes can point at entirely different directories, and fails
 * clearly for unknown types or a {@code neo4j} entry when Neo4j is
 * disabled.
 */
class NodeKnowledgeSourceResolverTest {

    private static final Neo4jProperties DISABLED_NEO4J = new Neo4jProperties(false, null, null, null, 20);

    @Test
    void resolvesDirectorySourcesByTheirConfiguredPath(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("doc.md"), "some knowledge content");
        NodeKnowledgeSourceResolver resolver = new NodeKnowledgeSourceResolver(null, DISABLED_NEO4J);

        List<String> results = resolver.resolve(
                List.of(Map.of("type", "directory", "path", root.toString())), "ignored query");

        assertThat(results).containsExactly("some knowledge content");
    }

    @Test
    void differentNodesCanResolveDifferentDirectories(@TempDir Path root) throws IOException {
        Path dirA = Files.createDirectory(root.resolve("a"));
        Path dirB = Files.createDirectory(root.resolve("b"));
        Files.writeString(dirA.resolve("file.md"), "content A");
        Files.writeString(dirB.resolve("file.md"), "content B");
        NodeKnowledgeSourceResolver resolver = new NodeKnowledgeSourceResolver(null, DISABLED_NEO4J);

        List<String> nodeOneResults = resolver.resolve(
                List.of(Map.of("type", "directory", "path", dirA.toString())), "q");
        List<String> nodeTwoResults = resolver.resolve(
                List.of(Map.of("type", "directory", "path", dirB.toString())), "q");

        assertThat(nodeOneResults).containsExactly("content A");
        assertThat(nodeTwoResults).containsExactly("content B");
    }

    @Test
    void emptySourceConfigListReturnsNoKnowledgeContext() {
        NodeKnowledgeSourceResolver resolver = new NodeKnowledgeSourceResolver(null, DISABLED_NEO4J);

        assertThat(resolver.resolve(List.of(), "query")).isEmpty();
    }

    @Test
    void throwsAClearErrorForAnUnknownSourceType() {
        NodeKnowledgeSourceResolver resolver = new NodeKnowledgeSourceResolver(null, DISABLED_NEO4J);

        assertThatThrownBy(() -> resolver.resolve(List.of(Map.of("type", "vector-db")), "q"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector-db");
    }

    @Test
    void throwsAClearErrorForANeo4jSourceWhenNeo4jIsDisabled() {
        NodeKnowledgeSourceResolver resolver = new NodeKnowledgeSourceResolver(null, DISABLED_NEO4J);

        assertThatThrownBy(() -> resolver.resolve(
                List.of(Map.of("type", "neo4j", "database", "domain-knowledge")), "q"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentic.knowledge.neo4j.enabled");
    }

    @Test
    void throwsAClearErrorWhenARequiredFieldIsMissing() {
        NodeKnowledgeSourceResolver resolver = new NodeKnowledgeSourceResolver(null, DISABLED_NEO4J);

        assertThatThrownBy(() -> resolver.resolve(List.of(Map.of("type", "directory")), "q"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }
}
