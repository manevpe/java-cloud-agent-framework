package io.github.manevpe.agentic.skill;

import io.github.manevpe.agentic.plugin.PluginManager;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@code skills} names referenced by a workflow YAML node
 * config to registered {@link Skill} beans, merging every built-in {@link
 * Skill} Spring bean with every {@link Skill} discovered from plugin jars
 * by {@link PluginManager} — the same merge-then-fail-fast-on-collision
 * pattern used by {@code AgentRegistry}/{@code ConditionRegistry}.
 */
@Component
public class SkillRegistry {

    private final Map<String, Skill> skillsByName;

    public SkillRegistry(List<Skill> springSkills, PluginManager pluginManager) {
        Map<String, Skill> merged = new HashMap<>();
        for (Skill skill : springSkills) {
            merged.put(skill.name(), skill);
        }
        for (Skill skill : pluginManager.loadSkills()) {
            if (merged.containsKey(skill.name())) {
                throw new IllegalStateException(
                        "Plugin skill '%s' collides with an already-registered skill of the same name"
                                .formatted(skill.name()));
            }
            merged.put(skill.name(), skill);
        }
        this.skillsByName = Map.copyOf(merged);
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(skillsByName.get(name));
    }

    public Skill resolve(String name) {
        return find(name).orElseThrow(() -> new IllegalStateException(
                "No skill registered under '%s'. Registered skills: %s".formatted(name, skillsByName.keySet())));
    }
}
