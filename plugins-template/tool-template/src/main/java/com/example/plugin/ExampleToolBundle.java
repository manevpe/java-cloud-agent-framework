package com.example.plugin;

import io.github.manevpe.agentic.tool.ToolBundle;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Template for a custom {@link ToolBundle} (a named, reusable set of LLM
 * tools, with no prompt-fragment opinion of its own — see {@code
 * ToolBundle}'s Javadoc for how this differs from a {@code Skill}), loaded
 * from an external jar via {@code ServiceLoader}. Copy this class (rename
 * freely), then:
 *
 * <ol>
 *   <li>Change {@link #name()} to a unique id — this is what an
 *       {@code Agent#requiredTools()} implementation, or a workflow
 *       YAML node's own {@code tools: [...]} config key, references to
 *       pull this bundle's tools in.</li>
 *   <li>Bundle whichever {@link ToolCallback}s the tool depends on in
 *       {@link #tools()} — {@link ToolCallbacks#from} converts any
 *       {@code @Tool}-annotated plain object (see {@link ExampleTool})
 *       into {@code ToolCallback}s, exactly like the framework's own
 *       agents do for their built-in tools.</li>
 *   <li>If an owning agent needs typed, non-tool-calling access to this
 *       bundle's backing object (e.g. to read/reset state between calls,
 *       the way {@code WorkspaceSetupTool} is used), override {@link
 *       #instance()} to return it; the agent then resolves it via {@code
 *       ToolRegistry#resolveInstance(String, Class)}. Left at the
 *       default ({@code this}) here since {@link ExampleTool} holds no
 *       state an agent would need direct access to.</li>
 *   <li>List this class under {@code
 *       src/main/resources/META-INF/services/io.github.manevpe.agentic.tool.ToolBundle}
 *       so {@code ServiceLoader} can find it.</li>
 * </ol>
 *
 * <p>Must be {@code public} with a {@code public} no-arg constructor, same
 * as the agent template's classes — see {@code ExampleAgent}'s Javadoc
 * (in {@code agent-template}) for why.
 */
public class ExampleToolBundle implements ToolBundle {

    private final ExampleTool exampleTool = new ExampleTool();

    @Override
    public String name() {
        return "example-plugin-tool";
    }

    @Override
    public List<ToolCallback> tools() {
        return List.of(ToolCallbacks.from(exampleTool));
    }
}
