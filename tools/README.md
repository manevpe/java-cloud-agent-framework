# Tools

Each directory under `tools/` is a standalone Gradle module publishing one
`ToolBundle` — a named, reusable set of LLM-invocable `@Tool` methods with
no prompt opinion of its own (see `io.github.manevpe.agentic.tool.ToolBundle`
and ADR-0008). Agents declare which bundles they need via
`Agent#requiredTools()` and/or a workflow node's own `tools: [...]` YAML
config; `ToolRegistry` resolves bundle names to tool lists at execution
time, merging built-in (Spring-bean) bundles with any plugin-provided ones
and failing startup on a name collision.

This is exactly the same plugin contract a third-party tool bundle uses —
see `docs/plugins.md` and `plugins-template/tool-template/` for the
generic mechanism and a copy-and-adapt starting point.

## Module index

| Module | Bundle name | Tools | Used by |
|---|---|---|---|
| [`tool-support`](tool-support/README.md) | *(none — pure helper library)* | — | every other tool module |
| [`file-read-tool`](file-read-tool/README.md) | `file-read` | `readRepoFile` | `planning-agent`, `conversational-planning-agent` |
| [`workspace-setup-tool`](workspace-setup-tool/README.md) | `workspace-setup` | `gitClone`, `listWorkspaceFiles`, `readWorkspaceFile`, `searchWorkspace` | `planning-agent`, `conversational-planning-agent`, `coding-agent` |
| [`file-edit-tool`](file-edit-tool/README.md) | `file-edit` | `writeWorkspaceFile`, `runWorkspaceCommand`, `diffWorkspace` | `coding-agent` |
| [`github-api-tool`](github-api-tool/README.md) | `github-api` | `listGithubOrgRepositories`, `searchGithubCode` | `coding-agent` |
| [`http-request-tool`](http-request-tool/README.md) | `http-request` | `fetchUrl` | *(available to any agent that adds it via node config)* |
| [`ask-human-tool`](ask-human-tool/README.md) | `ask-human` | `askHuman` | `conversational-planning-agent` |

## Conventions shared across every module

- **One bundle per module, named after its bundle** — the module's
  `ToolBundle#name()` (e.g. `"file-read"`) is what workflow YAML and
  `Agent#requiredTools()` reference; the module directory name matches it
  (`file-read-tool`).
- **`PluginContextAware` for anything with real collaborators.** Every
  bundle except `http-request-tool` (stateless, credential-free) and
  `tool-support` (not a bundle at all) implements
  `io.github.manevpe.agentic.plugin.PluginContextAware` alongside
  `ToolBundle`, so it works identically whether it's a built-in
  Spring-managed bean or a `ServiceLoader`-discovered plugin — see each
  module's own README and `docs/plugins.md`'s "Getting real collaborators
  via PluginContext" section.
- **`compileOnly(project(":core"))`.** Every module compiles against
  `:core`'s stable plugin API surface only (see `docs/plugins.md`'s
  "Plugin API surface" list) and never ships `:core` itself in its jar —
  the framework's own classloader already provides it at runtime.
- **Never return a blank `String` from a `@Tool` method.** Route any
  value that could legitimately be empty (an empty file, no diff yet, ...)
  through `tool-support`'s `ToolResults.orPlaceholder(value, placeholder)`
  first — see that module's README for the upstream Gemini bug this works
  around.
- **Per-call/per-turn state lives in `ThreadLocal`s, not instance
  fields.** Every bundle here is registered as a Spring singleton, but
  Spring AI invokes `@Tool` methods synchronously on the calling agent's
  own thread, and several workflow turns can run concurrently on
  different threads. Any bundle that needs to track state across several
  tool calls within one `LlmClient#complete()` invocation (opened
  workspaces, an in-flight HTTP request, a pending `askHuman` question)
  keys that state off the current thread — see `workspace-setup-tool` and
  `ask-human-tool` in particular.
