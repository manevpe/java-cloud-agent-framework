package io.github.manevpe.agentic.persistence.jpa;

import io.github.manevpe.agentic.persistence.AuditLogRepository;
import io.github.manevpe.agentic.persistence.AuditLogRepositoryContractTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class JpaAuditLogRepositoryTest extends AuditLogRepositoryContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AuditLogRepository repository;

    @Override
    protected AuditLogRepository repository() {
        return repository;
    }
}
