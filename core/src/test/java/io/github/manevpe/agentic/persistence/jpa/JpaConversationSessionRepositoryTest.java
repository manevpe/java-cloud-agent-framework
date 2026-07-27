package io.github.manevpe.agentic.persistence.jpa;

import io.github.manevpe.agentic.persistence.ConversationSessionRepository;
import io.github.manevpe.agentic.persistence.ConversationSessionRepositoryContractTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class JpaConversationSessionRepositoryTest extends ConversationSessionRepositoryContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ConversationSessionRepository repository;

    @Override
    protected ConversationSessionRepository repository() {
        return repository;
    }
}
