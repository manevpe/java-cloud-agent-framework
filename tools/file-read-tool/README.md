# file-read-tool

Bundle name: **`file-read`**

A minimal, single-file GitHub reader — the cheapest possible way for a
model to peek at one file's content before committing to a full
`gitClone` (see [`workspace-setup-tool`](../workspace-setup-tool/README.md)).

## Tools exposed

| Tool | Parameters | Returns |
|---|---|---|
| `readRepoFile` | `repository` (`owner/repo`), `path` (file path within the repo) | The file's content as text, or `"(file is empty)"` if blank |

Reads the file from the repository's default branch via
`GitHubClient#readFile(repository, path, null)`.

## Used by

- `planning-agent` — inspects existing repository code before finalizing
  an implementation plan.
- `conversational-planning-agent` — same purpose, in its `askHuman`-driven
  conversational planning loop.

## Collaborators

Implements `PluginContextAware`; `setPluginContext` wires in
`context.gitHubClient()`. A `FileReadTool(GitHubClient)` constructor is
also available for direct construction (e.g. Spring bean wiring in
`:core`) outside the `ServiceLoader`/`PluginContext` path.

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

- Never clones or opens a sandbox pod — always the cheapest, fastest
  option when only one file's content is needed. Prefer this (and
  `github-api-tool`) over `gitClone` when a model is still narrowing down
  which repository/file is relevant.
- Uses `tool-support`'s `ToolResults.orPlaceholder(...)` so an empty file
  never comes back as a blank `String` (see that module's README for why).
