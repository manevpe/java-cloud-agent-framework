package io.github.manevpe.agentic.skill;

import io.github.manevpe.agentic.plugin.PluginFixture;
import io.github.manevpe.agentic.plugin.PluginManager;
import io.github.manevpe.agentic.plugin.PluginProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link SkillRegistry}'s merge-plugins behaviour — no Spring context needed. */
class SkillRegistryTest {

    @Test
    void resolvesBothBuiltInAndPluginProvidedSkillsByName(@TempDir Path pluginDir) {
        PluginFixture.buildFixturePluginJar(pluginDir.resolve("fixture-plugin.jar"));
        PluginManager pluginManager = new PluginManager(new PluginProperties(pluginDir));
        StubSkill builtIn = new StubSkill("built-in-skill");

        SkillRegistry registry = new SkillRegistry(List.of(builtIn), pluginManager);

        assertThat(registry.resolve("built-in-skill")).isSameAs(builtIn);
        assertThat(registry.resolve(PluginFixture.SKILL_NAME).promptFragment())
                .isEqualTo("Fixture skill prompt fragment.");
    }

    @Test
    void throwsOnDuplicateNameBetweenBuiltInAndPlugin(@TempDir Path pluginDir) {
        PluginFixture.buildFixturePluginJar(pluginDir.resolve("fixture-plugin.jar"));
        PluginManager pluginManager = new PluginManager(new PluginProperties(pluginDir));
        StubSkill colliding = new StubSkill(PluginFixture.SKILL_NAME);

        assertThatThrownBy(() -> new SkillRegistry(List.of(colliding), pluginManager))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PluginFixture.SKILL_NAME);
    }

    @Test
    void findReturnsEmptyForUnknownSkill() {
        PluginManager noPlugins = new PluginManager(new PluginProperties(null));
        SkillRegistry registry = new SkillRegistry(List.of(), noPlugins);

        assertThat(registry.find("no-such-skill")).isEmpty();
    }

    private record StubSkill(String name) implements Skill {
        @Override
        public String promptFragment() {
            return "stub";
        }

        @Override
        public List<ToolCallback> tools() {
            return List.of();
        }
    }
}
