package io.github.manevpe.agentic.plugin;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.engine.EdgeCondition;
import io.github.manevpe.agentic.skill.Skill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link PluginManager} discovers a real jar's {@code
 * META-INF/services}-declared {@link Agent}/{@link EdgeCondition}/{@link
 * Skill} implementations, and safely no-ops when no plugin directory is
 * configured — no Spring context needed, {@link PluginManager} is usable
 * standalone.
 */
class PluginManagerTest {

    @Test
    void loadsAgentConditionAndSkillFromAFixturePluginJar(@TempDir Path pluginDir) {
        PluginFixture.buildFixturePluginJar(pluginDir.resolve("fixture-plugin.jar"));

        PluginManager pluginManager = new PluginManager(new PluginProperties(pluginDir));

        assertThat(pluginManager.loadAgents()).extracting(Agent::type).containsExactly(PluginFixture.AGENT_TYPE);
        assertThat(pluginManager.loadConditions()).hasSize(1);
        assertThat(pluginManager.loadConditions().get(0).test(null)).isTrue();
        assertThat(pluginManager.loadSkills()).extracting(Skill::name).containsExactly(PluginFixture.SKILL_NAME);
    }

    @Test
    void returnsEmptyListsWhenNoDirectoryIsConfigured() {
        PluginManager pluginManager = new PluginManager(new PluginProperties(null));

        assertThat(pluginManager.loadAgents()).isEmpty();
        assertThat(pluginManager.loadConditions()).isEmpty();
        assertThat(pluginManager.loadSkills()).isEmpty();
    }

    @Test
    void returnsEmptyListsWhenTheConfiguredDirectoryDoesNotExist(@TempDir Path pluginDir) {
        PluginManager pluginManager = new PluginManager(new PluginProperties(pluginDir.resolve("does-not-exist")));

        assertThat(pluginManager.loadAgents()).isEmpty();
    }

    @Test
    void returnsEmptyListsWhenTheDirectoryHasNoJars(@TempDir Path pluginDir) {
        PluginManager pluginManager = new PluginManager(new PluginProperties(pluginDir));

        assertThat(pluginManager.loadAgents()).isEmpty();
    }
}
