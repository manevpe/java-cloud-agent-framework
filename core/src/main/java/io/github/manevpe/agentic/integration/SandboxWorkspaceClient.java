package io.github.manevpe.agentic.integration;

import java.util.List;

/**
 * Port for the sandbox-backed repo-exploration and code-editing workspace
 * (see ADR-0005). Backs an LLM tool-calling loop that needs synchronous,
 * second-scale round-trips — so it clones a repository into an isolated,
 * short-lived workspace and lets the caller run several read/list/search/
 * write/run/diff commands against that same checkout before closing it.
 *
 * <p>Workspace addressing: {@link #open} returns a {@link WorkspaceHandle}
 * carrying a {@code workspaceId} that must be passed back into every
 * subsequent call — this is what lets a single LLM turn open more
 * than one workspace (e.g. two related repositories) and keep addressing
 * them correctly across several tool-calling round-trips within that turn.
 *
 * <p>Lifecycle: a workspace is scoped to a single {@code PlanningAgent}
 * or {@code CodingAgent} turn — {@code WorkspaceSetupTool} tracks every
 * workspace it opens during that call and closes all of them once it
 * returns (in a {@code finally} block), regardless of how the LLM's
 * tool-calling loop went. Workspaces are deliberately not kept alive
 * across a paused workflow's human-clarification gap (e.g. waiting on a
 * Slack reply, which can take hours to days) — implementations should
 * also enforce their own bounded lifetime (e.g. a pod-level deadline) as
 * a safety net against a caller that crashes before calling {@link
 * #close}.
 *
 * <p>{@link #writeFile}, {@link #runCommand}, and {@link #diff} let the
 * coding agent implement a change and run its build/test command in the
 * same isolated workspace used for exploration, rather than handing a
 * flat instructions string to a separate opaque process.
 */
public interface SandboxWorkspaceClient {

    /**
     * Clones {@code repository} (at {@code ref}, or the default branch if
     * {@code null}) into a new isolated workspace and returns a handle to
     * it.
     */
    WorkspaceHandle open(String repository, String ref);

    /**
     * Lists file paths under {@code directory} (relative to the repo root) in the given workspace.
     *
     * @param maxDepth how many directory levels below {@code directory} to recurse into;
     *                 {@code null} (or non-positive) means unlimited depth
     */
    List<String> listFiles(String workspaceId, String directory, Integer maxDepth);

    /** Reads a single file's content (relative to the repo root) from the given workspace. */
    String readFile(String workspaceId, String path);

    /** Searches file contents in the given workspace for {@code pattern} (a regular expression), returning matching lines. */
    List<String> search(String workspaceId, String pattern);

    /** Writes (creating or overwriting) a single file's content in the given workspace. */
    void writeFile(String workspaceId, String path, String content);

    /**
     * Runs an arbitrary shell command (e.g. a build/test command) inside the
     * given workspace, with the repository checkout as the working
     * directory, and returns its exit code and combined stdout/stderr.
     */
    CommandResult runCommand(String workspaceId, String command);

    /**
     * @return a unified diff of every change made in the workspace (via
     *         {@link #writeFile} or {@link #runCommand}) since it was
     *         cloned, relative to the checked-out ref
     */
    String diff(String workspaceId);

    /** Tears down the workspace (e.g. deletes the backing pod) and releases its resources. */
    void close(String workspaceId);

    record WorkspaceHandle(String workspaceId, String repository) {
    }

    record CommandResult(int exitCode, String output) {
    }
}
