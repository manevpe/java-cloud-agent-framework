package io.github.manevpe.agentic.integration.llm.tool;

import io.github.manevpe.agentic.integration.SandboxWorkspaceClient;
import io.github.manevpe.agentic.plugin.PluginContext;
import io.github.manevpe.agentic.plugin.PluginContextAware;
import io.github.manevpe.agentic.tool.ToolBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes {@link SandboxWorkspaceClient} as a set of LLM-invocable tools —
 * {@code gitClone}, {@code listWorkspaceFiles}, {@code readWorkspaceFile},
 * {@code searchWorkspace} — so the planning agent's model can clone a
 * repository into an isolated sandbox workspace and then browse/search it
 * like a real checkout, rather than being limited to {@link
 * FileReadTool}'s single-file-at-a-time GitHub API reads. See
 * ADR-0005.
 *
 * <p>Workspace lifecycle: this is a singleton bean, but the app may run
 * several planning turns concurrently (different tickets on different
 * threads), so opened-workspace tracking is kept in a {@link ThreadLocal}
 * rather than a plain instance field — Spring AI's tool-calling loop
 * invokes tool methods synchronously on the same thread that called
 * {@code LlmClient#complete}, so each {@code draftPlan()} call sees only
 * the workspaces it opened. Repeated {@code gitClone} calls for the same
 * repository/ref within one such call are also deduplicated (see {@link
 * #gitClone}) so a model that re-clones what it already has doesn't leak
 * a fresh pod every time, and a hard cap ({@link
 * #MAX_DISTINCT_WORKSPACES_PER_CALL}) on genuinely distinct
 * repository/ref pods per turn stops an uncertain model from cloning
 * many different candidate repositories at once. {@code
 * LlmPlanningAssistant} calls {@link #closeAllOpenedInCurrentCall()} in
 * a {@code finally} block right after its {@code draftPlan()} call
 * returns, on that same thread.
 */
public class WorkspaceSetupTool implements ToolBundle, PluginContextAware {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSetupTool.class);

    /**
     * Hard ceiling on distinct repository/ref sandbox pods a single LLM
     * turn may have open at once. Without this, an uncertain model can
     * call {@code gitClone} for many different candidate repositories in
     * one turn (each a genuinely distinct key, so the same-repo dedup
     * above doesn't help) — observed live to spawn ~35 pods in one turn
     * and OOM-kill the app itself. Once this limit is hit, gitClone fails
     * fast with a tool error steering the model toward the cheap,
     * pod-free exploration tools (readRepoFile / the GitHub API tool)
     * to narrow down the right repository before cloning it.
     */
    static final int MAX_DISTINCT_WORKSPACES_PER_CALL = 3;

    private SandboxWorkspaceClient workspaceClient;
    private final ThreadLocal<List<String>> openedWorkspaceIds = ThreadLocal.withInitial(ArrayList::new);
    // Keyed by "repository@ref" (ref defaults to "" meaning the default
    // branch) so a model that calls gitClone multiple times for the same
    // repository/ref within one call — e.g. retrying after an unrelated
    // tool error, or simply re-checking out what it already has — reuses
    // the existing pod instead of leaking a brand-new one per call. A
    // genuinely different repository or ref still gets its own workspace.
    private final ThreadLocal<Map<String, String>> workspaceIdsByRepoRef = ThreadLocal.withInitial(HashMap::new);
    // Counts every distinct repository/ref gitClone has *attempted* to
    // open in this turn, successful or not. This — not
    // workspaceIdsByRepoRef's size — is what MAX_DISTINCT_WORKSPACES_PER_CALL
    // is enforced against: a slot is reserved before workspaceClient.open()
    // is even called, so a repository whose pod fails to become ready or
    // whose clone fails (e.g. a slow/stuck image pull) still permanently
    // consumes a slot instead of leaving the model free to retry it (or
    // try yet another candidate repository) an unbounded number of times.
    // A live incident hit exactly this gap: failed opens never reached
    // the success cache, so the cap never engaged and dozens of orphaned
    // pods were created before the underlying failure was fixed.
    private final ThreadLocal<Integer> distinctWorkspaceAttempts = ThreadLocal.withInitial(() -> 0);

    /** No-arg constructor for {@code ServiceLoader} discovery — see {@link #setPluginContext}. */
    public WorkspaceSetupTool() {
    }

    public WorkspaceSetupTool(SandboxWorkspaceClient workspaceClient) {
        this.workspaceClient = workspaceClient;
    }

    @Override
    public void setPluginContext(PluginContext context) {
        this.workspaceClient = context.sandboxWorkspaceClient();
    }

    @Override
    public String name() {
        return "workspace-setup";
    }

    @Override
    public List<ToolCallback> tools() {
        return List.of(ToolCallbacks.from(this));
    }

    @Tool(description = "Clones a GitHub repository into an isolated sandbox workspace and returns a "
            + "workspaceId. Pass that workspaceId into listWorkspaceFiles/readWorkspaceFile/searchWorkspace "
            + "to browse the real checkout. Safe to call again for the same repository/ref you already "
            + "cloned in this turn — it reuses the existing workspace instead of creating a new one.")
    public String gitClone(
            @ToolParam(description = "the repository in 'owner/repo' form") String repository,
            @ToolParam(description = "branch/tag/commit to check out; omit for the default branch", required = false)
            String ref) {
        if (repository == null || repository.isBlank()) {
            // Fail fast with a clear tool error rather than opening a pod
            // for a garbage/placeholder value (e.g. an unresolved
            // "unknown/unknown" default) that will only fail its git
            // clone anyway — every failed attempt would otherwise leak a
            // brand-new workspace pod each retry.
            throw new IllegalArgumentException(
                    "repository must be a real 'owner/repo' value, e.g. 'my-org/my-repo' — got: '" + repository + "'");
        }
        String key = repository + "@" + (ref == null ? "" : ref);
        Map<String, String> cached = workspaceIdsByRepoRef.get();
        String existing = cached.get(key);
        if (existing != null) {
            return existing;
        }
        int attempts = distinctWorkspaceAttempts.get();
        if (attempts >= MAX_DISTINCT_WORKSPACES_PER_CALL) {
            throw new IllegalStateException(
                    "Already attempted %d distinct sandbox workspace(s) in this turn (limit: %d) — narrow "
                            .formatted(attempts, MAX_DISTINCT_WORKSPACES_PER_CALL)
                            + "down to the correct repository using readRepoFile or the GitHub API tool "
                            + "(listGithubOrgRepositories/searchGithubCode) before cloning another one.");
        }
        // Reserve this slot before the (possibly slow/failing) open() call
        // itself, so a failed attempt still permanently counts against the
        // cap rather than leaving the model free to retry indefinitely.
        distinctWorkspaceAttempts.set(attempts + 1);
        SandboxWorkspaceClient.WorkspaceHandle handle = workspaceClient.open(repository, ref);
        openedWorkspaceIds.get().add(handle.workspaceId());
        cached.put(key, handle.workspaceId());
        return handle.workspaceId();
    }

    @Tool(description = "Lists file paths under a directory in a previously cloned workspace (see gitClone).")
    public List<String> listWorkspaceFiles(
            @ToolParam(description = "workspaceId returned by gitClone") String workspaceId,
            @ToolParam(description = "directory to list, relative to the repo root; e.g. 'src/main/java'")
            String directory,
            @ToolParam(description = "how many directory levels to recurse into; omit for unlimited depth", required = false)
            Integer maxDepth) {
        return workspaceClient.listFiles(workspaceId, directory, maxDepth);
    }

    @Tool(description = "Reads a single file's content from a previously cloned workspace (see gitClone).")
    public String readWorkspaceFile(
            @ToolParam(description = "workspaceId returned by gitClone") String workspaceId,
            @ToolParam(description = "path to the file, relative to the repo root") String path) {
        return ToolResults.orPlaceholder(workspaceClient.readFile(workspaceId, path), "(file is empty)");
    }

    @Tool(description = "Searches file contents in a previously cloned workspace for a regular expression, "
            + "returning matching lines with file/line context.")
    public List<String> searchWorkspace(
            @ToolParam(description = "workspaceId returned by gitClone") String workspaceId,
            @ToolParam(description = "a regular expression to search for") String pattern) {
        return workspaceClient.search(workspaceId, pattern);
    }

    /**
     * Closes every workspace this thread opened since the last call to
     * this method (or since this thread first used the tool). Called by
     * {@code LlmPlanningAssistant} once per {@code draftPlan()}
     * invocation — see ADR-0005 for why workspaces are scoped to a single
     * LLM planning turn rather than kept alive across a paused workflow's
     * human-clarification gap.
     */
    public void closeAllOpenedInCurrentCall() {
        List<String> ids = openedWorkspaceIds.get();
        for (String workspaceId : ids) {
            try {
                workspaceClient.close(workspaceId);
            } catch (Exception e) {
                log.warn("Failed to close sandbox workspace '{}' — it will rely on its active-deadline "
                        + "safety net to be cleaned up eventually", workspaceId, e);
            }
        }
        openedWorkspaceIds.remove();
        workspaceIdsByRepoRef.remove();
        distinctWorkspaceAttempts.remove();
    }
}
