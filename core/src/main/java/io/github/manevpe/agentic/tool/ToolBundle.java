package io.github.manevpe.agentic.tool;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * A named, reusable bundle of LLM tools ({@link ToolCallback}s) — this
 * framework's unit of "which tools does an agent need", independent of
 * any particular agent. Deliberately distinct from {@link
 * io.github.manevpe.agentic.skill.Skill}: a {@code Skill} pairs tools with
 * a system-prompt fragment for an optional, conversational capability an
 * agent opts into by name from workflow YAML (e.g. {@code grill-me}); a
 * {@code ToolBundle} is just the tools themselves, with no prompt
 * opinion, meant to be composed by an agent that already has its own
 * prompt (e.g. {@code file-read}, {@code workspace-setup}).
 *
 * <p>Registered the same way {@link io.github.manevpe.agentic.agent.Agent}
 * and {@link io.github.manevpe.agentic.skill.Skill} are: implement this
 * interface and either (a) register it as a Spring {@code @Component} for
 * a built-in tool bundle shipped in this repo, or (b) declare it under
 * {@code META-INF/services/io.github.manevpe.agentic.tool.ToolBundle} in
 * a plugin jar — see {@code PluginManager} and ADR-0008.
 *
 * <p>An {@link io.github.manevpe.agentic.agent.Agent} declares the tool
 * bundle names it cannot function without via {@code
 * Agent#requiredTools()}; those are resolved and merged into the agent's
 * tool list automatically. A workflow YAML node can additionally list
 * supplemental bundle names under its own {@code tools: [...]} config key
 * for optional, operator-added capability, resolved by the agent itself
 * via {@link ToolRegistry#resolveTools(List)} at execution time.
 */
public interface ToolBundle {

    /** Unique name this bundle is referenced by (e.g. from {@code Agent#requiredTools()} or workflow YAML). */
    String name();

    /** The tools this bundle contributes, ready to hand to an {@code LlmClient#complete} call. */
    List<ToolCallback> tools();

    /**
     * The concrete object backing this bundle's {@code @Tool}-annotated
     * methods, for an owning agent that also needs typed, non-tool-calling
     * access to it (e.g. {@code WorkspaceSetupTool#closeAllOpenedInCurrentCall()}
     * for per-call cleanup). Defaults to {@code this}, true whenever the
     * bundle implementation itself hosts the {@code @Tool} methods (the
     * common case for every built-in tool bundle).
     */
    default Object instance() {
        return this;
    }
}
