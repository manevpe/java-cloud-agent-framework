package io.github.manevpe.agentic.engine;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.config.AgentProperties;
import io.github.manevpe.agentic.plugin.PluginFixture;
import io.github.manevpe.agentic.plugin.PluginManager;
import io.github.manevpe.agentic.plugin.PluginProperties;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AgentRegistry}'s merge-plugins/disable-types behaviour — no Spring context needed. */
class AgentRegistryTest {

    @Test
    void resolvesBothBuiltInAndPluginProvidedAgentsByType(@TempDir Path pluginDir) {
        PluginFixture.buildFixturePluginJar(pluginDir.resolve("fixture-plugin.jar"));
        PluginManager pluginManager = new PluginManager(new PluginProperties(pluginDir));
        StubAgent builtIn = new StubAgent("built-in-agent");

        AgentRegistry registry = new AgentRegistry(List.of(builtIn), pluginManager, new AgentProperties(List.of()));

        assertThat(registry.resolve("built-in-agent")).isSameAs(builtIn);
        assertThat(registry.resolve(PluginFixture.AGENT_TYPE)).isNotNull();
    }

    @Test
    void throwsOnDuplicateTypeBetweenBuiltInAndPlugin(@TempDir Path pluginDir) {
        PluginFixture.buildFixturePluginJar(pluginDir.resolve("fixture-plugin.jar"));
        PluginManager pluginManager = new PluginManager(new PluginProperties(pluginDir));
        StubAgent colliding = new StubAgent(PluginFixture.AGENT_TYPE);

        assertThatThrownBy(() -> new AgentRegistry(List.of(colliding), pluginManager, new AgentProperties(List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PluginFixture.AGENT_TYPE);
    }

    @Test
    void disabledTypesAreRemovedFromResolution() {
        PluginManager noPlugins = new PluginManager(new PluginProperties(null));
        StubAgent agent = new StubAgent("disable-me");

        AgentRegistry registry = new AgentRegistry(
                List.of(agent), noPlugins, new AgentProperties(List.of("disable-me")));

        assertThatThrownBy(() -> registry.resolve("disable-me")).isInstanceOf(IllegalStateException.class);
    }

    private record StubAgent(String type) implements Agent {
        @Override
        public AgentResult execute(NodeDefinition node, WorkflowState state) {
            return new AgentResult.Continue(state);
        }
    }
}
