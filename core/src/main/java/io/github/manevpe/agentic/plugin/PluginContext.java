package io.github.manevpe.agentic.plugin;

import io.github.manevpe.agentic.integration.GitHubClient;
import io.github.manevpe.agentic.integration.HumanInteractionClientRegistry;
import io.github.manevpe.agentic.integration.JiraClient;
import io.github.manevpe.agentic.integration.LlmClient;
import io.github.manevpe.agentic.integration.SlackClient;
import io.github.manevpe.agentic.integration.SandboxWorkspaceClient;
import io.github.manevpe.agentic.persistence.ConversationSessionRepository;
import io.github.manevpe.agentic.tool.ToolRegistry;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * Curated facade a {@code ServiceLoader}-discovered plugin ({@link
 * io.github.manevpe.agentic.agent.Agent}, {@link
 * io.github.manevpe.agentic.engine.EdgeCondition}, or {@link
 * io.github.manevpe.agentic.skill.Skill}) can use to reach the same
 * Spring-managed collaborators a built-in, {@code @Component}-registered
 * implementation would get via constructor injection.
 *
 * <p><b>Why this exists</b>: {@code ServiceLoader} requires a public
 * no-arg constructor (see ADR-0007), so plugin classes can never receive
 * dependencies through Spring's {@code ApplicationContext} the normal
 * way. A plugin implementing {@link PluginContextAware} instead receives
 * one of these after {@code ServiceLoader} instantiates it (see {@link
 * PluginManager}), before it's registered — anything beyond that point
 * (agent/condition/skill resolution, workflow execution) treats built-in
 * and plugin-provided implementations identically. See ADR-0007 for the
 * full rationale, including why {@link #llmClient()} specifically must
 * come from this context rather than a plugin building its own {@code
 * ChatClient}: only the Spring-managed bean carries this application's
 * {@code ToolExecutionExceptionProcessor} and other tool-calling
 * customizations.
 *
 * <p>Every method has a default that throws {@link
 * UnsupportedOperationException} — a plugin only needs to worry about the
 * collaborators it actually uses, and a test double can be a bare
 * {@code new PluginContext() {}} without implementing the rest. In a real
 * deployment every method below is always available (backed by real
 * beans, gated behind the same {@code agentic.<x>.enabled} flags as the
 * built-in agents use — e.g. a plugin calling {@link #jiraClient()} gets
 * {@code LoggingJiraClient} unless {@code agentic.jira.enabled=true},
 * exactly like a built-in agent would).
 *
 * <p><b>Trust model</b>: {@link #environment()} exposes every application
 * property, credentials included — consistent with ADR-0007's plugin
 * trust model (a handful of organization-authored jars, not arbitrary
 * untrusted third-party code), not a sandboxed/least-privilege boundary.
 */
public interface PluginContext {

    default LlmClient llmClient() {
        throw unavailable("llmClient");
    }

    default JiraClient jiraClient() {
        throw unavailable("jiraClient");
    }

    default GitHubClient gitHubClient() {
        throw unavailable("gitHubClient");
    }

    default SlackClient slackClient() {
        throw unavailable("slackClient");
    }

    default SandboxWorkspaceClient sandboxWorkspaceClient() {
        throw unavailable("sandboxWorkspaceClient");
    }

    default HumanInteractionClientRegistry humanInteractionClientRegistry() {
        throw unavailable("humanInteractionClientRegistry");
    }

    default ConversationSessionRepository conversationSessionRepository() {
        throw unavailable("conversationSessionRepository");
    }

    /**
     * Resolves named {@link io.github.manevpe.agentic.tool.ToolBundle}s
     * (both built-in and plugin-provided) — an {@code Agent} uses this to
     * resolve the tool bundles it declares via {@code
     * Agent#requiredTools()}, plus any supplemental bundle names a
     * workflow YAML node lists under its own {@code tools: [...]} config.
     * See ADR-0008.
     */
    default ToolRegistry toolRegistry() {
        throw unavailable("toolRegistry");
    }

    /** Arbitrary application/plugin configuration, e.g. {@code environment().getProperty("agentic.jira.base-url")}. */
    default Environment environment() {
        throw unavailable("environment");
    }

    /**
     * Resolves {@code classpath:}/{@code file:}/... {@link
     * org.springframework.core.io.Resource} locations (e.g. a plugin's own
     * bundled prompt templates) against the plugin's own jar/classloader —
     * never the framework core's — so {@code classpath:} locations find
     * resources packaged inside the plugin jar itself.
     */
    default ResourceLoader resourceLoader() {
        throw unavailable("resourceLoader");
    }

    private static UnsupportedOperationException unavailable(String member) {
        return new UnsupportedOperationException(
                "PluginContext#" + member + "() is not available in this context");
    }
}
