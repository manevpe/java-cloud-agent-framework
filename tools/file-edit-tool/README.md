# file-edit-tool

Bundle name: **`file-edit`**

Exposes the write/run/diff half of `SandboxWorkspaceClient` as LLM tools,
so the coding agent's model can actually *implement* a change (not just
explore one) against a workspace opened via
[`workspace-setup-tool`](../workspace-setup-tool/README.md)'s `gitClone`,
then run the repository's own build/test command and retrieve the
resulting diff — all inside the same isolated pod.

## Tools exposed

| Tool | Parameters | Returns |
|---|---|---|
| `writeWorkspaceFile` | `workspaceId`, `path` (relative to repo root), `content` (full new file content) | *(void)* — creates or overwrites the file |
| `runWorkspaceCommand` | `workspaceId`, `command` (shell command, run with the repo checkout as working directory) | `SandboxWorkspaceClient.CommandResult` — exit code + combined stdout/stderr |
| `diffWorkspace` | `workspaceId` | A unified diff of every change made so far via `writeWorkspaceFile`/`runWorkspaceCommand`, or `"(no changes yet)"` |

## Used by

- `coding-agent` — implements the plan (or a PR-review-driven amendment)
  by writing files, running the repository's own build/test command, and
  retrieving the final diff to push.

## Deliberately split from `workspace-setup-tool`

Planning only ever needs read-only exploration, so `planning-agent`/
`conversational-planning-agent` are wired with `workspace-setup-tool`
alone and never gain write/run capability — only `coding-agent` is wired
with both bundles, addressing the same `workspaceId` across them since
they share the same backing `SandboxWorkspaceClient`.

## Collaborators

Implements `PluginContextAware`; `setPluginContext` wires in
`context.sandboxWorkspaceClient()`. A `FileEditTool(SandboxWorkspaceClient)`
constructor is also available for direct construction outside the
`ServiceLoader`/`PluginContext` path.

## Dependencies

```kotlin
dependencies {
    compileOnly(project(":core"))
    implementation(project(":tools:tool-support"))
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
```

## Notes

- Uses `tool-support`'s `ToolResults.orPlaceholder(...)` so a workspace
  with no changes yet never returns a blank diff `String`.
- The typical tool-calling sequence a coding agent's model follows:
  `gitClone` (workspace-setup-tool) → `listWorkspaceFiles`/
  `readWorkspaceFile`/`searchWorkspace` (workspace-setup-tool) →
  `writeWorkspaceFile` → `runWorkspaceCommand` (check the exit code to
  decide whether tests passed) → `diffWorkspace` once done.
