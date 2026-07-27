package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** Binds {@code agentic.workflows.*} application properties. */
@ConfigurationProperties(prefix = "agentic.workflows")
public record WorkflowProperties(Path directory) {
}
