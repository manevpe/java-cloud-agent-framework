package io.github.manevpe.agentic.integration.llm.tool;

import io.github.manevpe.agentic.integration.SandboxWorkspaceClient;
import io.github.manevpe.agentic.plugin.PluginContext;
import io.github.manevpe.agentic.plugin.PluginContextAware;
import io.github.manevpe.agentic.tool.ToolBundle;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * Exposes the write/run/diff half of {@link SandboxWorkspaceClient} as LLM
 * tools — {@code writeWorkspaceFile}, {@code runWorkspaceCommand}, {@code
 * diffWorkspace} — so the coding agent's model can actually implement a
 * change (not just explore one) against a workspace opened via {@link
 * WorkspaceSetupTool#gitClone}, then run the repository's own build/test
 * command and retrieve the resulting diff, all inside the same isolated
 * pod. See the ADR recorded when the sandbox build/test {@code Job}
 * dispatch model was replaced with this tool-calling model.
 *
 * <p>Deliberately kept separate from {@link WorkspaceSetupTool}: planning
 * only ever needs read-only exploration, so {@code LlmPlanningAssistant}
 * is wired with {@link WorkspaceSetupTool} alone and never gains write/run
 * capability — only the coding-implementation assistant is wired with
 * both tool classes, addressing the same {@code workspaceId} across them
 * since they share the same backing {@link SandboxWorkspaceClient}.
 */
public class FileEditTool implements ToolBundle, PluginContextAware {

    private SandboxWorkspaceClient workspaceClient;

    /** No-arg constructor for {@code ServiceLoader} discovery — see {@link #setPluginContext}. */
    public FileEditTool() {
    }

    public FileEditTool(SandboxWorkspaceClient workspaceClient) {
        this.workspaceClient = workspaceClient;
    }

    @Override
    public void setPluginContext(PluginContext context) {
        this.workspaceClient = context.sandboxWorkspaceClient();
    }

    @Override
    public String name() {
        return "file-edit";
    }

    @Override
    public List<ToolCallback> tools() {
        return List.of(ToolCallbacks.from(this));
    }

    @Tool(description = "Writes (creating or overwriting) a single file's content in a previously cloned "
            + "workspace (see gitClone). Use this to implement the requested code change.")
    public void writeWorkspaceFile(
            @ToolParam(description = "workspaceId returned by gitClone") String workspaceId,
            @ToolParam(description = "path to the file, relative to the repo root") String path,
            @ToolParam(description = "the file's full new content") String content) {
        workspaceClient.writeFile(workspaceId, path, content);
    }

    @Tool(description = "Runs a shell command (e.g. a build or test command) inside a previously cloned "
            + "workspace (see gitClone), with the repository checkout as the working directory. Returns the "
            + "command's exit code and combined stdout/stderr. Use this to run the repository's own build/test "
            + "command after making changes, and check the exit code to decide whether tests passed.")
    public SandboxWorkspaceClient.CommandResult runWorkspaceCommand(
            @ToolParam(description = "workspaceId returned by gitClone") String workspaceId,
            @ToolParam(description = "the shell command to run") String command) {
        return workspaceClient.runCommand(workspaceId, command);
    }

    @Tool(description = "Returns a unified diff of every change made so far (via writeWorkspaceFile or "
            + "runWorkspaceCommand) in a previously cloned workspace (see gitClone). Call this once you're done "
            + "implementing the change and its tests pass, to get the final diff to report back.")
    public String diffWorkspace(
            @ToolParam(description = "workspaceId returned by gitClone") String workspaceId) {
        return ToolResults.orPlaceholder(workspaceClient.diff(workspaceId), "(no changes yet)");
    }
}
