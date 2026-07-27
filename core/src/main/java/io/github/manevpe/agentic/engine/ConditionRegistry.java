package io.github.manevpe.agentic.engine;

import io.github.manevpe.agentic.plugin.PluginManager;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the {@code condition} name referenced by an edge in workflow
 * YAML (e.g. {@code hasOpenQuestions}) to the registered {@link
 * EdgeCondition} bean of that name.
 *
 * <p>Merges every Spring-registered {@link EdgeCondition} bean (named after
 * its {@code @Bean} method, e.g. {@code WorkflowConditions#hasOpenQuestions})
 * with every {@link EdgeCondition} discovered from external plugin jars by
 * {@link PluginManager}. Plugin-provided conditions have no
 * Spring bean name to borrow, so they're registered under their
 * implementing class's simple name, decapitalized (e.g. a class {@code
 * MyCondition} is referenced from YAML as {@code myCondition}) — document
 * this convention for plugin authors in {@code plugins-template/}.
 */
@Component
class ConditionRegistry {

    private final Map<String, EdgeCondition> conditionsByName;

    ConditionRegistry(Map<String, EdgeCondition> springConditionsByName, PluginManager pluginManager) {
        Map<String, EdgeCondition> merged = new HashMap<>(springConditionsByName);
        for (EdgeCondition condition : pluginManager.loadConditions()) {
            String name = decapitalize(condition.getClass().getSimpleName());
            if (merged.containsKey(name)) {
                throw new IllegalStateException(
                        "Plugin condition '%s' collides with an already-registered condition of the same name"
                                .formatted(name));
            }
            merged.put(name, condition);
        }
        this.conditionsByName = Map.copyOf(merged);
    }

    private static String decapitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    EdgeCondition resolve(String name) {
        EdgeCondition condition = conditionsByName.get(name);
        if (condition == null) {
            throw new IllegalStateException(
                    "No condition bean named '%s'. Registered conditions: %s"
                            .formatted(name, conditionsByName.keySet()));
        }
        return condition;
    }
}
