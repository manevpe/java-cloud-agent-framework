package io.github.manevpe.agentic.skill;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * A named, reusable bundle of a system-prompt fragment plus the tools it
 * depends on — this framework's equivalent of a Copilot CLI/Claude Code
 * "skill". Any conversational agent can reference a skill by {@link
 * #name()} from its workflow node YAML config (e.g. {@code skills:
 * [grill-me]}) to fold its {@link #promptFragment()} into that agent's
 * system prompt and merge its {@link #tools()} into that turn's tool list,
 * without the agent needing to know how the skill is implemented.
 *
 * <p>Registered the same way {@link io.github.manevpe.agentic.agent.Agent}
 * is: implement this interface and either (a) register it as a Spring
 * {@code @Component} for a built-in skill shipped in this repo, or (b)
 * declare it under {@code META-INF/services/io.github.manevpe.agentic.skill.Skill}
 * in a plugin jar dropped into the configured plugin directory — see {@code
 * PluginManager} and {@code plugins-template/skill-template}.
 *
 * <p>{@link ToolCallback} (Spring AI's own tool currency) is reused
 * directly rather than inventing a parallel tool abstraction — it's
 * already what every agent's own {@code tools} field is typed as (see
 * {@code PlanningAgent}, {@code ConversationalPlanningAgent}).
 */
public interface Skill {

    /** Unique name this skill is referenced by from workflow YAML (e.g. {@code "grill-me"}). */
    String name();

    /**
     * Text folded into the owning agent's system prompt when this skill is
     * active — instructions describing when/how to use this skill's tools.
     */
    String promptFragment();

    /** Tools this skill contributes to the owning agent's tool list while active. */
    List<ToolCallback> tools();
}
