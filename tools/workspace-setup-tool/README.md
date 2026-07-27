# workspace-setup-tool

Bundle name: **`workspace-setup`**

Exposes `SandboxWorkspaceClient` as a set of LLM-invocable tools so an
agent's model can clone a repository into an isolated sandbox workspace
and then browse/search it like a real checkout, rather than being limited
to [`file-read-tool`](../file-read-tool/README.md)'s single-file-at-a-time
GitHub API reads. See ADR-0005 for the sandbox workspace architecture.

## Tools exposed

| Tool | Parameters | Returns |
|---|---|---|
| `gitClone` | `repository` (`owner/repo`), `ref` (branch/tag/commit, optional — default branch if omitted) | `workspaceId` string |
| `listWorkspaceFiles` | `workspaceId`, `directory` (relative to repo root), `maxDepth` (optional, unlimited if omitted) | `List<String>` of file paths |
| `readWorkspaceFile` | `workspaceId`, `path` (relative to repo root) | File content as text, or `"(file is empty)"` |
| `searchWorkspace` | `workspaceId`, `pattern` (a regex) | `List<String>` of matching lines with file/line context |

## Read-only vs. read/write

This module is deliberately **read-only** exploration. Write/run/diff
capability against the same workspace lives in a separate bundle,
[`file-edit-tool`](../file-edit-tool/README.md) — the planning agent only
ever needs to look around, so it's wired with this bundle alone; only
`coding-agent` gets both, addressing the same `workspaceId` across them
since they share the same backing `SandboxWorkspaceClient`.

## Used by

- `planning-agent`
- `conversational-planning-agent`
- `coding-agent` (paired with `file-edit-tool`)

## Per-turn workspace lifecycle and safety caps

This is a Spring singleton bean, but several workflow turns (different
tickets) can run concurrently on different threads, so all opened-workspace
tracking is kept in `ThreadLocal`s — Spring AI invokes tool methods
synchronously on the same thread that called `LlmClient#complete`, so
each planning/coding turn only ever sees the workspaces *it* opened.

- **Same-repo/ref dedup:** repeated `gitClone` calls for the same
  `repository@ref` within one turn reuse the existing workspace instead
  of leaking a fresh pod every time.
- **Hard cap — `MAX_DISTINCT_WORKSPACES_PER_CALL = 3`:** a hard ceiling on
  distinct repository/ref sandbox pods a single turn may open. Without
  this, an uncertain model can call `gitClone` for many different
  candidate repositories in one turn (each a genuinely distinct key, so
  the dedup above doesn't help) — observed live to spawn ~35 pods in one
  turn and OOM-kill the app. Once hit, `gitClone` fails fast with a tool
  error steering the model toward the cheap, pod-free exploration tools
  (`readRepoFile` / `github-api-tool`) to narrow down the right repository
  first.
- **Failed attempts still count against the cap.** A slot is reserved
  *before* `workspaceClient.open()` is even called, so a repository whose
  pod fails to become ready (e.g. a slow/stuck image pull) still
  permanently consumes a slot rather than leaving the model free to retry
  it indefinitely. A live incident hit exactly this gap before the fix:
  failed opens never reached the success cache, so the cap never engaged
  and dozens of orphaned pods were created.
- **`closeAllOpenedInCurrentCall()`** — called by the owning agent
  (`PlanningAgent`, `ConversationalPlanningAgent`, `CodingAgent`) in a
  `finally` block right after its `LlmClient#complete()` call returns, on
  that same thread. Workspaces are scoped to a single LLM turn, never kept
  alive across the async gap while a paused workflow waits on a human
  reply (see ADR-0005).

## Collaborators

Implements `PluginContextAware`; `setPluginContext` wires in
`context.sandboxWorkspaceClient()`. A `WorkspaceSetupTool(SandboxWorkspaceClient)`
constructor is also available for direct construction outside the
`ServiceLoader`/`PluginContext` path.

## Dependencies

```kotlin
dependencies {
    compileOnly(project(":core"))
    compileOnly("org.slf4j:slf4j-api")
    implementation(project(":tools:tool-support"))
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
```

## Notes

- An owning agent that needs typed access to
  `closeAllOpenedInCurrentCall()` (not itself a `@Tool` method) resolves
  the concrete instance via `ToolRegistry#resolveInstance("workspace-setup",
  WorkspaceSetupTool.class)` rather than casting a generic `ToolBundle`.
