package io.github.manevpe.agentic.plugin;

import io.github.manevpe.agentic.integration.GitHubClient;
import io.github.manevpe.agentic.integration.HumanInteractionClientRegistry;
import io.github.manevpe.agentic.integration.JiraClient;
import io.github.manevpe.agentic.integration.LlmClient;
import io.github.manevpe.agentic.integration.SlackClient;
import io.github.manevpe.agentic.integration.SandboxWorkspaceClient;
import io.github.manevpe.agentic.persistence.ConversationSessionRepository;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * The real, Spring-wired {@link PluginContext}: every method simply
 * returns the corresponding application bean, so a plugin using it gets
 * the exact same singletons (and thus the exact same {@code
 * agentic.<x>.enabled}-gated real-vs-logging-stub behaviour) a built-in
 * agent would via constructor injection. {@link #resourceLoader()}
 * returns core's own classloader-bound loader; {@link PluginManager}
 * wraps this bean in a decorator that overrides just that one method to
 * point at each plugin's own classloader before handing the context to a
 * {@link PluginContextAware} plugin — see {@link PluginManager}'s Javadoc.
 */
@Component
public class SpringPluginContext implements PluginContext {

    private final LlmClient llmClient;
    private final JiraClient jiraClient;
    private final GitHubClient gitHubClient;
    private final SlackClient slackClient;
    private final SandboxWorkspaceClient sandboxWorkspaceClient;
    private final HumanInteractionClientRegistry humanInteractionClientRegistry;
    private final ConversationSessionRepository conversationSessionRepository;
    private final Environment environment;
    private final ResourceLoader resourceLoader;

    public SpringPluginContext(
            LlmClient llmClient,
            JiraClient jiraClient,
            GitHubClient gitHubClient,
            SlackClient slackClient,
            SandboxWorkspaceClient sandboxWorkspaceClient,
            HumanInteractionClientRegistry humanInteractionClientRegistry,
            ConversationSessionRepository conversationSessionRepository,
            Environment environment,
            ResourceLoader resourceLoader) {
        this.llmClient = llmClient;
        this.jiraClient = jiraClient;
        this.gitHubClient = gitHubClient;
        this.slackClient = slackClient;
        this.sandboxWorkspaceClient = sandboxWorkspaceClient;
        this.humanInteractionClientRegistry = humanInteractionClientRegistry;
        this.conversationSessionRepository = conversationSessionRepository;
        this.environment = environment;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public LlmClient llmClient() {
        return llmClient;
    }

    @Override
    public JiraClient jiraClient() {
        return jiraClient;
    }

    @Override
    public GitHubClient gitHubClient() {
        return gitHubClient;
    }

    @Override
    public SlackClient slackClient() {
        return slackClient;
    }

    @Override
    public SandboxWorkspaceClient sandboxWorkspaceClient() {
        return sandboxWorkspaceClient;
    }

    @Override
    public HumanInteractionClientRegistry humanInteractionClientRegistry() {
        return humanInteractionClientRegistry;
    }

    @Override
    public ConversationSessionRepository conversationSessionRepository() {
        return conversationSessionRepository;
    }

    @Override
    public Environment environment() {
        return environment;
    }

    @Override
    public ResourceLoader resourceLoader() {
        return resourceLoader;
    }
}
