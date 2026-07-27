package io.github.manevpe.agentic.tool;

import org.springframework.ai.tool.ToolCallback;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Merges every built-in {@link ToolBundle} Spring bean with every {@link
 * ToolBundle} discovered from plugin jars by {@code PluginManager} into a
 * single name-keyed registry — the same merge-then-fail-fast-on-collision
 * pattern used by {@code AgentRegistry}/{@code ConditionRegistry}/{@code
 * SkillRegistry}.
 *
 * <p>Deliberately <b>not</b> a Spring {@code @Component}: {@code
 * PluginManager} builds one of these directly inside its own constructor
 * (see its Javadoc) right after loading plugin tool bundles and right
 * before injecting {@code PluginContext} into any {@code Agent}s that
 * need to resolve tools from it — avoiding a circular Spring bean
 * dependency that would otherwise exist between {@code PluginContext}
 * (which exposes this registry to agents) and {@code PluginManager}
 * (which builds it).
 */
public final class ToolRegistry {

    private final Map<String, ToolBundle> bundlesByName;

    private ToolRegistry(Map<String, ToolBundle> bundlesByName) {
        this.bundlesByName = bundlesByName;
    }

    public static ToolRegistry merge(List<ToolBundle> builtIn, List<ToolBundle> pluginProvided) {
        Map<String, ToolBundle> merged = new HashMap<>();
        for (ToolBundle bundle : builtIn) {
            merged.put(bundle.name(), bundle);
        }
        for (ToolBundle bundle : pluginProvided) {
            if (merged.containsKey(bundle.name())) {
                throw new IllegalStateException(
                        "Plugin tool bundle '%s' collides with an already-registered tool bundle of the same name"
                                .formatted(bundle.name()));
            }
            merged.put(bundle.name(), bundle);
        }
        return new ToolRegistry(Map.copyOf(merged));
    }

    public Optional<ToolBundle> find(String name) {
        return Optional.ofNullable(bundlesByName.get(name));
    }

    public ToolBundle resolve(String name) {
        return find(name).orElseThrow(() -> new IllegalStateException(
                "No tool bundle registered under '%s'. Registered tool bundles: %s"
                        .formatted(name, bundlesByName.keySet())));
    }

    /** Resolves {@code name} and casts its {@link ToolBundle#instance()} to {@code type}. */
    public <T> T resolveInstance(String name, Class<T> type) {
        Object instance = resolve(name).instance();
        if (!type.isInstance(instance)) {
            throw new IllegalStateException(
                    "Tool bundle '%s' instance is of type %s, not the requested %s"
                            .formatted(name, instance.getClass().getName(), type.getName()));
        }
        return type.cast(instance);
    }

    /**
     * Resolves every name in {@code names} and concatenates their tools, in
     * order, wrapping each in a {@link LoggingToolCallback} — see its
     * Javadoc for why every tool call needs this logging.
     */
    public List<ToolCallback> resolveTools(List<String> names) {
        return names.stream()
                .flatMap(name -> resolve(name).tools().stream())
                .<ToolCallback>map(LoggingToolCallback::new)
                .toList();
    }
}
