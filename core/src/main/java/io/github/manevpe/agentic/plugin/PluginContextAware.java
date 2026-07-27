package io.github.manevpe.agentic.plugin;

/**
 * Optional hook a {@code ServiceLoader}-discovered {@link
 * io.github.manevpe.agentic.agent.Agent}, {@link
 * io.github.manevpe.agentic.engine.EdgeCondition}, or {@link
 * io.github.manevpe.agentic.skill.Skill} implements to receive a {@link
 * PluginContext} right after {@code ServiceLoader} instantiates it (see
 * {@link PluginManager}), since constructor injection isn't available for
 * {@code ServiceLoader}-loaded classes (see ADR-0007). A plugin with no
 * external dependencies (e.g. a pure routing {@code EdgeCondition}) simply
 * doesn't implement this interface.
 */
public interface PluginContextAware {

    void setPluginContext(PluginContext context);
}
