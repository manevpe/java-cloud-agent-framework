package io.github.manevpe.agentic.config;

import io.github.manevpe.agentic.workflow.WorkflowDefinitionRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the (framework-agnostic) {@link WorkflowConfigLoader} into Spring:
 * loads every workflow YAML file from {@code agentic.workflows.directory} at
 * startup into a {@link WorkflowDefinitionRegistry} bean.
 */
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowConfigAutoConfiguration {

    @Bean
    public WorkflowConfigLoader workflowConfigLoader() {
        return new WorkflowConfigLoader();
    }

    @Bean
    public WorkflowDefinitionRegistry workflowDefinitionRegistry(
            WorkflowConfigLoader loader, WorkflowProperties properties) {
        return new WorkflowDefinitionRegistry(loader.loadAll(properties.directory()));
    }
}
