package io.github.manevpe.agentic.integration.neo4j;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link Neo4jKnowledgeSource} against a real Neo4j instance —
 * seeds a couple of {@code (:KnowledgeItem)} nodes, then checks that
 * {@link Neo4jKnowledgeSource#query} matches on keyword containment and
 * respects the configured result limit.
 */
@Testcontainers
class Neo4jKnowledgeSourceTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5-community").withoutAuthentication();

    static Driver driver;

    @BeforeAll
    static void setUpDriverAndSeedData() {
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.none());
        try (Session session = driver.session()) {
            session.run("""
                    CREATE (:KnowledgeItem {text: 'The payments service uses idempotency keys for retries.',
                                             keywords: ['payments', 'idempotency', 'retries']})
                    CREATE (:KnowledgeItem {text: 'Authentication is handled via OAuth2 tokens issued by the identity service.',
                                             keywords: ['authentication', 'oauth2', 'identity']})
                    CREATE (:KnowledgeItem {text: 'The billing pipeline runs nightly batch jobs.',
                                             keywords: ['billing', 'batch', 'pipeline']})
                    """).consume();
        }
    }

    @AfterAll
    static void closeDriver() {
        driver.close();
    }

    @Test
    void returnsKnowledgeItemsWhoseKeywordsContainAQueryTerm() {
        Neo4jKnowledgeSource knowledgeSource = new Neo4jKnowledgeSource(driver, "neo4j", 20);

        List<String> results = knowledgeSource.query("How do payments handle retries?");

        assertThat(results).contains("The payments service uses idempotency keys for retries.");
        assertThat(results).doesNotContain("The billing pipeline runs nightly batch jobs.");
    }

    @Test
    void respectsTheConfiguredResultLimit() {
        Neo4jKnowledgeSource knowledgeSource = new Neo4jKnowledgeSource(driver, "neo4j", 1);

        // "service" matches both the payments and authentication items' keywords via their text,
        // but keywords themselves are matched — use a term appearing in multiple keyword lists.
        List<String> results = knowledgeSource.query("pipeline batch payments idempotency");

        assertThat(results).hasSize(1);
    }

    @Test
    void returnsNoResultsWhenNothingMatches() {
        Neo4jKnowledgeSource knowledgeSource = new Neo4jKnowledgeSource(driver, "neo4j", 20);

        assertThat(knowledgeSource.query("kubernetes networking")).isEmpty();
    }
}
