package io.github.manevpe.agentic.engine;

import io.github.manevpe.agentic.plugin.PluginFixture;
import io.github.manevpe.agentic.plugin.PluginManager;
import io.github.manevpe.agentic.plugin.PluginProperties;
import io.github.manevpe.agentic.workflow.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link ConditionRegistry}'s merge-plugins behaviour — no Spring context needed. */
class ConditionRegistryTest {

    @Test
    void resolvesBothSpringAndPluginProvidedConditionsByName(@TempDir Path pluginDir) {
        PluginFixture.buildFixturePluginJar(pluginDir.resolve("fixture-plugin.jar"));
        PluginManager pluginManager = new PluginManager(new PluginProperties(pluginDir));
        EdgeCondition springCondition = state -> true;

        ConditionRegistry registry = new ConditionRegistry(
                Map.of("mySpringCondition", springCondition), pluginManager);

        assertThat(registry.resolve("mySpringCondition")).isSameAs(springCondition);
        // Plugin-provided EdgeCondition has no Spring bean name — registered
        // under its class's simple name, decapitalized (fixtureCondition).
        assertThat(registry.resolve("fixtureCondition").test(WorkflowState.empty())).isTrue();
    }

    @Test
    void throwsOnDuplicateNameBetweenSpringAndPlugin(@TempDir Path pluginDir) {
        PluginFixture.buildFixturePluginJar(pluginDir.resolve("fixture-plugin.jar"));
        PluginManager pluginManager = new PluginManager(new PluginProperties(pluginDir));
        EdgeCondition colliding = state -> false;

        assertThatThrownBy(() -> new ConditionRegistry(Map.of("fixtureCondition", colliding), pluginManager))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixtureCondition");
    }
}
