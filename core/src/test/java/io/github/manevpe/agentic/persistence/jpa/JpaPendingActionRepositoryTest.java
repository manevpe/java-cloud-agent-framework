package io.github.manevpe.agentic.persistence.jpa;

import io.github.manevpe.agentic.persistence.PendingActionRepository;
import io.github.manevpe.agentic.persistence.PendingActionRepositoryContractTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class JpaPendingActionRepositoryTest extends PendingActionRepositoryContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PendingActionRepository repository;

    @Override
    protected PendingActionRepository repository() {
        return repository;
    }
}
