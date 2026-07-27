package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code agentic.github.*}, {@code agentic.jira.*}, and {@code
 * agentic.slack.*} property groups for the real, credential-authenticated
 * external integration clients (see {@code RestGitHubClient}, {@code
 * RestJiraClient}, {@code RestSlackClient}). Each real client is itself
 * gated behind its own {@code agentic.<x>.enabled} flag (default {@code
 * false}), mirroring {@code SandboxAutoConfiguration}/{@code
 * Neo4jAutoConfiguration} — a plain local run with none of these enabled
 * falls back to the existing {@code Logging*Client} stubs with zero extra
 * config or external calls.
 */
@Configuration
@EnableConfigurationProperties({GitHubProperties.class, JiraProperties.class, SlackProperties.class})
public class IntegrationAutoConfiguration {
}
