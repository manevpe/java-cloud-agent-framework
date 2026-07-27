---
name: adding-agent-or-tool-module
description: Scaffold a new Agent or ToolBundle Gradle module in java-cloud-agent-framework and keep every place that must know about it in sync (settings.gradle.kts, agents-integration-tests, Dockerfile). Use whenever adding, renaming, or removing an out-of-the-box agent/tool module.
---

# Adding an Agent or Tool Module

Every out-of-the-box agent and shareable tool in this repo is its own plain-Java Gradle module, loaded by `core` at runtime via the `ServiceLoader` plugin mechanism (ADR-0010/ADR-0014) — **never** via Spring component-scanning. A new module is a jar dropped into `agentic.plugins.directory`, indistinguishable from a third-party plugin a downstream team writes themselves.

Because of this, adding a module means **four separate places must be updated together**, plus the module itself. Missing any one of them causes a confusing failure (module compiles but is invisible at runtime, or the Docker image doesn't ship it, or the integration test suite doesn't exercise it).

## When to Activate

- User asks to add a new agent (e.g. "add an agent that posts a Slack summary")
- User asks to add a new shareable tool (a `ToolBundle`)
- User asks to rename, remove, or split an existing agent/tool module
- User asks "how do I add a new agent/tool to this repo?"

## The Four Sync Points (+ the module itself)

1. **`settings.gradle.kts`** — add `"agents:my-new-agent"` (or `"tools:my-new-tool"`) to the `include(...)` block, in the appropriate section (tools before agents, alphabetical-ish within each).
2. **The module itself** — see scaffold below.
3. **`agents-integration-tests/build.gradle.kts`** — add the same Gradle path string to the `pluginModules` list. This one list drives both the `dependencies {}` block (compiles it in) and the `aggregatePlugins` `Copy` task (stages its jar alongside every other plugin jar in `build/plugins`, which the real `PluginManager`/`ServiceLoader` scans during the `@SpringBootTest`). Without this, the new module is never exercised end-to-end.
4. **`Dockerfile`** — add `:agents:my-new-agent:jar` (or `:tools:my-new-tool:jar`) to the `RUN ./gradlew --no-daemon ...` list in the build stage, AND make sure it's covered by the wildcard `COPY --from=build /workspace/agents/*/build/libs/*.jar` / `/workspace/tools/*/build/libs/*.jar` lines in the `runtime-with-default-modules` stage (it will be automatically, since those are globs — but the explicit jar-task list is NOT a glob and must be updated by hand).

If you forget #1, Gradle doesn't know the module exists at all. If you forget #3, `./gradlew :agents-integration-tests:test` silently keeps passing without ever loading your new code. If you forget #4, the module builds and tests fine locally but is absent from the production image.

## Module Scaffold

Use an existing module of the same kind as your template — `agents/planning-agent` for a simple single-call agent, `agents/coding-agent` for one with tool-calling/sandbox use, `tools/file-read-tool` for a minimal tool, `tools/github-api-tool` for one with an external REST client dependency.

A module directory looks like:

```
agents/my-new-agent/
├── build.gradle.kts
├── README.md                                    ← follow the established per-module template (see any existing agents/*/README.md)
└── src/main/
    ├── java/io/github/manevpe/agentic/agent/mynewagent/MyNewAgent.java
    └── resources/
        ├── META-INF/services/io.github.manevpe.agentic.agent.Agent   ← one line: fully-qualified class name
        └── prompts/my-new-agent.st                                    ← Spring AI StringTemplate prompt(s), if any
```

For a tool module, the service file is `META-INF/services/io.github.manevpe.agentic.tool.ToolBundle` instead, and there's usually no `prompts/` directory.

### `build.gradle.kts` template

```kotlin
plugins {
    id("java")
}

dependencies {
    compileOnly(project(":core"))
    // Add a shareable tool dependency here if this agent calls one, e.g.:
    // implementation(project(":tools:file-read-tool"))
}
```

The root `build.gradle.kts`'s `configure(subprojects.filter { ... })` block already applies the Java toolchain (25), `-parameters` compiler flag, and other shared config to every `:agents:*`/`:tools:*` module — do not repeat it here. `compileOnly(project(":core"))` (not `implementation`) is deliberate: at runtime `core` and its whole classpath are already on the JVM's classpath (the plugin jar only adds its own code), so bundling `core` into the plugin jar would just bloat it.

### Agent class shape

Implement `io.github.manevpe.agentic.agent.Agent`. Look at an existing agent for the exact interface — key points to replicate:
- A no-arg (or Spring-injectable-but-ServiceLoader-instantiable) constructor; agents are **not** Spring beans, so don't rely on `@Autowired`/`@Value` — any Spring-managed collaborator (LLM `ChatModel`, `NodeKnowledgeSourceResolver`, HTTP clients, etc.) is delivered via `PluginContextAware` callback methods, not constructor injection.
- `type()` returns the string used in workflow YAML's `agent:` field (and in `META-INF/services` — the FQCN — is how `ServiceLoader`/`AgentRegistry` finds it; `type()` is how YAML *references* it).
- Node-specific behavior (which LLM profile, which knowledge sources, thresholds, mode flags like `CodingAgent`'s `mode: implement`/`respond-to-review`) comes from the node's own `config: {}` map in YAML — never hardcode environment- or workflow-specific values in the Java class.
- If the agent needs per-turn scratch state that shouldn't leak across concurrent workflow threads, use a `ThreadLocal`, following `CodingAgent`'s pattern (see its Javadoc) — don't add mutable instance fields.

### Tool class shape

Implement `io.github.manevpe.agentic.tool.ToolBundle`. Return one or more callable tool methods (Spring AI `@Tool`-annotated or the bundle's own registration mechanism — check `tools/tool-support` for shared helpers, e.g. `ToolResults.orPlaceholder` for graceful missing-input handling). Keep each tool method's job narrow and side-effect-documented (a file-read tool only reads, a file-edit tool only writes — composition happens in the agent/workflow, not inside one mega-tool).

## Testing Expectations

Per-module unit tests are optional and rare in this repo today (only `tools/workspace-setup-tool` has one, `WorkspaceSetupToolTest`) — add one if your module has non-trivial pure logic worth isolating (parsing, formatting, edge-case branching), but don't feel obligated to build a full mocked-Spring-context test per module.

The real correctness bar is **`agents-integration-tests`**: a `src/main`-less module that boots `core` as an actual `@SpringBootTest`, with every module in `pluginModules` (including yours, once added per step 3 above) staged into `build/plugins` and loaded through the genuine `PluginManager`/`ServiceLoader` path — the same path production uses. If your new agent/tool participates in one of the example workflows (or needs a new test workflow YAML — see the `adding-workflow-yaml` skill), add/extend a scenario there rather than writing an isolated unit test that mocks everything your module actually depends on.

## Documentation

Every module has a `README.md` at its root following the same shape (Purpose / Config keys / Example YAML usage / Dependencies / Testing notes — look at 2-3 existing ones for the exact conventions expected in this repo, e.g. `agents/coding-agent/README.md` for an agent with a `mode` flag, `tools/github-api-tool/README.md` for a tool with external credentials). Add your new module to the index table in `agents/README.md` or `tools/README.md` as well.

## Verifying

```bash
./gradlew :agents:my-new-agent:build          # compiles + runs any unit tests
./gradlew :agents-integration-tests:test      # exercises it end-to-end via ServiceLoader
```

If the second command doesn't even attempt to load your module (no log line, no effect on outcome), you likely missed step 3 above.
