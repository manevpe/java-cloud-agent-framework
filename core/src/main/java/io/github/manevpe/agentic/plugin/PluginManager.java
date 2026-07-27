package io.github.manevpe.agentic.plugin;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.engine.EdgeCondition;
import io.github.manevpe.agentic.skill.Skill;
import io.github.manevpe.agentic.tool.ToolBundle;
import io.github.manevpe.agentic.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Discovers custom {@link Agent}/{@link EdgeCondition}/{@link Skill}
 * implementations shipped in external jars — this is how the framework
 * loads capabilities from other repositories without them being on this
 * application's own build classpath, letting operators bring their own
 * agents/tools/skills.
 *
 * <p>Deliberately uses the JDK's own {@link ServiceLoader} mechanism
 * (each jar declares its implementations under {@code META-INF/services/
 * <interface-fully-qualified-name>}) rather than dynamic Spring bean
 * registration: {@code ServiceLoader} works with any jar dropped in after
 * the fact, needs no build-time dependency from this app onto the plugin
 * (or vice versa) beyond the shared API packages ({@code agent}, {@code
 * engine.EdgeCondition}, {@code skill}), and is the same mechanism the
 * templates under {@code plugins-template/} show how to use.
 *
 * <p>Every jar found directly under {@link PluginProperties#directory()}
 * (non-recursive) is added to a single {@link URLClassLoader}, whose
 * parent is this class's own loader so plugin code can reference the
 * framework's API types. All plugin jars therefore share one flat
 * classpath — acceptable for the expected use case (a handful of
 * organization-authored plugin jars, not arbitrary untrusted third-party
 * code), but means two plugin jars must not declare clashing package-private
 * class names for the same package. If {@code directory} is unset or
 * doesn't exist, every {@code loadX()} method simply returns an empty list.
 *
 * <p>After {@code ServiceLoader} instantiates each plugin, any loaded
 * {@link Agent}/{@link EdgeCondition}/{@link Skill} implementing {@link
 * PluginContextAware} receives a {@link PluginContext} — see ADR-0007.
 * That context is the injected {@link PluginContext} bean (normally
 * {@code SpringPluginContext}), decorated so {@link
 * PluginContext#resourceLoader()} resolves against the plugin's own
 * classloader rather than core's, so a plugin's bundled {@code
 * classpath:} resources (e.g. prompt templates) resolve correctly.
 */
@Component
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final List<Agent> agents;
    private final List<EdgeCondition> conditions;
    private final List<Skill> skills;
    private final List<ToolBundle> toolBundles;
    private final ToolRegistry toolRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    public PluginManager(PluginProperties properties, PluginContext coreContext) {
        ClassLoader pluginClassLoader = createPluginClassLoader(properties.directory());
        this.toolBundles = load(ToolBundle.class, pluginClassLoader);
        // Built before any Agent/Skill receives its PluginContext (below),
        // so PluginContext#toolRegistry() is always ready by the time an
        // agent's own setPluginContext resolves its requiredTools() — see
        // ADR-0008 for why this is built inline here rather than as a
        // separate Spring bean depending back on this PluginManager.
        this.toolRegistry = ToolRegistry.merge(List.of(), toolBundles);
        PluginContext contextForPlugins =
                withPluginClassLoaderResourceLoader(coreContext, pluginClassLoader, toolRegistry);
        this.agents = load(Agent.class, pluginClassLoader);
        this.conditions = load(EdgeCondition.class, pluginClassLoader);
        this.skills = load(Skill.class, pluginClassLoader);
        injectContext(toolBundles, contextForPlugins);
        injectContext(agents, contextForPlugins);
        injectContext(conditions, contextForPlugins);
        injectContext(skills, contextForPlugins);
        if (!agents.isEmpty() || !conditions.isEmpty() || !skills.isEmpty() || !toolBundles.isEmpty()) {
            log.info("Loaded {} plugin agent(s), {} plugin condition(s), {} plugin skill(s), "
                            + "{} plugin tool bundle(s) from '{}'",
                    agents.size(), conditions.size(), skills.size(), toolBundles.size(), properties.directory());
        }
    }

    /**
     * Test/standalone convenience: no {@link PluginContext} available, so
     * any {@link PluginContextAware} plugin loaded this way gets a no-op
     * one (every method throws {@link UnsupportedOperationException} —
     * fine for tests that don't exercise context-dependent plugins).
     */
    public PluginManager(PluginProperties properties) {
        this(properties, new PluginContext() {
        });
    }

    public List<Agent> loadAgents() {
        return agents;
    }

    public List<EdgeCondition> loadConditions() {
        return conditions;
    }

    public List<Skill> loadSkills() {
        return skills;
    }

    public List<ToolBundle> loadToolBundles() {
        return toolBundles;
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    private static ClassLoader createPluginClassLoader(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            if (directory != null) {
                log.debug("Plugin directory '{}' does not exist; no plugins will be loaded", directory);
            }
            return PluginManager.class.getClassLoader();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            URL[] jarUrls = entries
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .map(PluginManager::toUrl)
                    .toArray(URL[]::new);
            if (jarUrls.length == 0) {
                log.debug("Plugin directory '{}' contains no jar files", directory);
                return PluginManager.class.getClassLoader();
            }
            return new URLClassLoader(jarUrls, PluginManager.class.getClassLoader());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list plugin directory " + directory, e);
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("Invalid plugin jar path: " + path, e);
        }
    }

    private static <T> List<T> load(Class<T> serviceType, ClassLoader classLoader) {
        return ServiceLoader.load(serviceType, classLoader).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    /**
     * Wraps {@code coreContext} so {@link PluginContext#resourceLoader()}
     * resolves against the plugin jar's own classloader (so {@code
     * classpath:} resources bundled inside the plugin, e.g. prompt
     * templates, are found) while every other method delegates unchanged
     * to the real Spring-wired context.
     */
    private static PluginContext withPluginClassLoaderResourceLoader(
            PluginContext coreContext, ClassLoader pluginClassLoader, ToolRegistry toolRegistry) {
        ResourceLoader pluginResourceLoader = new DefaultResourceLoader(pluginClassLoader);
        return new PluginContext() {
            @Override
            public io.github.manevpe.agentic.integration.LlmClient llmClient() {
                return coreContext.llmClient();
            }

            @Override
            public io.github.manevpe.agentic.integration.JiraClient jiraClient() {
                return coreContext.jiraClient();
            }

            @Override
            public io.github.manevpe.agentic.integration.GitHubClient gitHubClient() {
                return coreContext.gitHubClient();
            }

            @Override
            public io.github.manevpe.agentic.integration.SlackClient slackClient() {
                return coreContext.slackClient();
            }

            @Override
            public io.github.manevpe.agentic.integration.SandboxWorkspaceClient sandboxWorkspaceClient() {
                return coreContext.sandboxWorkspaceClient();
            }

            @Override
            public io.github.manevpe.agentic.integration.HumanInteractionClientRegistry humanInteractionClientRegistry() {
                return coreContext.humanInteractionClientRegistry();
            }

            @Override
            public io.github.manevpe.agentic.persistence.ConversationSessionRepository conversationSessionRepository() {
                return coreContext.conversationSessionRepository();
            }

            @Override
            public Environment environment() {
                return coreContext.environment();
            }

            @Override
            public ResourceLoader resourceLoader() {
                return pluginResourceLoader;
            }

            @Override
            public ToolRegistry toolRegistry() {
                return toolRegistry;
            }
        };
    }

    /**
     * Calls {@link PluginContextAware#setPluginContext(PluginContext)} on
     * every element of {@code plugins} that opts into it, ignoring the rest.
     */
    private static void injectContext(List<?> plugins, PluginContext context) {
        for (Object plugin : plugins) {
            if (plugin instanceof PluginContextAware aware) {
                aware.setPluginContext(context);
            }
        }
    }
}
