package com.example.plugin;

import io.github.manevpe.agentic.skill.Skill;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Template for a custom skill (a named prompt fragment + tool bundle),
 * loaded from an external jar via {@code ServiceLoader}. Copy this class
 * (rename freely), then:
 *
 * <ol>
 *   <li>Change {@link #name()} to a unique id — this is what a
 *       conversational agent's workflow YAML references via {@code
 *       skills: [example-skill]}.</li>
 *   <li>Write {@link #promptFragment()}: instructions describing when/how
 *       the model should use this skill's tools, folded into the owning
 *       agent's system prompt while the skill is active.</li>
 *   <li>Bundle whichever {@link ToolCallback}s the skill depends on in
 *       {@link #tools()} — {@link ToolCallbacks#from} converts any {@code
 *       @Tool}-annotated plain object (see {@link ExampleTool}) into
 *       {@code ToolCallback}s, exactly like the framework's own agents do
 *       for their built-in tools.</li>
 *   <li>List this class under {@code
 *       src/main/resources/META-INF/services/io.github.manevpe.agentic.skill.Skill}
 *       so {@code ServiceLoader} can find it.</li>
 * </ol>
 *
 * <p>Must be {@code public} with a {@code public} no-arg constructor, same
 * as the agent template's classes — see {@code ExampleAgent}'s Javadoc
 * (in {@code agent-template}) for why.
 */
public class ExampleSkill implements Skill {

    private final ExampleTool exampleTool = new ExampleTool();

    @Override
    public String name() {
        return "example-plugin-skill";
    }

    @Override
    public String promptFragment() {
        return "You have access to a lookUpFact tool for retrieving example facts. "
                + "Use it whenever the user asks about a topic you're unsure of.";
    }

    @Override
    public List<ToolCallback> tools() {
        return List.of(ToolCallbacks.from(exampleTool));
    }
}
