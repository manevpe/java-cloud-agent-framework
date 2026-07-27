# AGENTS.md

Guidance for AI coding agents (Copilot CLI, Claude Code, Cursor, Aider,
etc.) working in this repository. Human contributors: `README.md` is the
project overview; this file is agent-oriented operational guidance.

## What this repo is

`java-cloud-agent-framework` is a Java/Spring Boot 4.1 runtime that executes
durable, pausable/resumable **workflow graphs** of **agents**, defined in
YAML, via LangGraph4j. The out-of-the-box workflow automates a Jira
ticket → implementation plan → PR → PR-review-response flow, but the
runtime itself is domain-agnostic — see `README.md`'s intro before
assuming anything is software-delivery-specific.

Read `README.md` first for the full picture (architecture diagrams, tech
stack, module index). Read `docs/adr/README.md` for *why* things are
built the way they are before changing anything architectural — this
repo is ADR-driven; a change that contradicts an accepted ADR should
either respect it or come with a new/updated ADR (see "Recording
architectural decisions" below).

## Module map

Multi-module Gradle build (Kotlin DSL), Java 25 toolchain throughout:

| Module | What it is |
|---|---|
| `core/` | The deployable Spring Boot app itself — engine, webhook ingress, persistence, plugin loading, all integration clients. Ships with **zero built-in agents**. |
| `agents/<name>-agent/` | One Gradle module (plain jar) per built-in `Agent`. See `agents/README.md`. |
| `tools/<name>-tool/` | One Gradle module (plain jar) per shareable `ToolBundle`. See `tools/README.md`. |
| `agents-integration-tests/` | End-to-end flow tests loading every built-in agent/tool jar via the same `ServiceLoader` mechanism used in production. |
| `plugins-template/` | Copy-and-adapt starting points (`agent-template/`, `tool-template/`, `skill-template/`) for a *separate* repo building its own plugins. |
| `workflows/examples/` | Runnable example workflow YAML. |
| `knowledge/` | Example domain-knowledge directories referenced by example workflows. |
| `docs/adr/` | Architecture Decision Records — read before any architectural change. |
| `docs/plugins.md` | The plugin contract (stable API surface, `ServiceLoader`, `PluginContext`). |
| `docs/local-testing.md` | Full walkthrough for running the reference workflow against real Jira/Slack/GitHub/LLM/k8s. |
| `deploy/helm/` | Kubernetes Helm chart. |

Every `agents/*`/`tools/*` module compiles `compileOnly(project(":core"))`
against `:core`'s stable plugin API surface only (see `docs/plugins.md`'s
"Plugin API surface" list) — never depend on a `:core` internal class
from these modules; if you need to, that's a signal the plugin API
surface itself may need extending (raise it, don't route around it).

## Build, test, run

```bash
./gradlew build -x test          # full multi-module build, no tests (fast sanity check)
./gradlew build                  # full build + all tests (several minutes)
./gradlew :core:compileJava       # fastest targeted compile check
./gradlew :core:test              # core module's own tests
./gradlew :core:test --tests "*ClassName*"   # targeted test class
./gradlew :agents:<name>-agent:test          # one agent module's tests
./gradlew :tools:<name>-tool:test            # one tool module's tests
./gradlew :agents-integration-tests:test     # full end-to-end flow tests
./gradlew bootRun                 # run core locally — every external
                                   # integration defaults to a logging
                                   # stub, zero built-in agents loaded
                                   # (bare core has no agent/tool jars)
```

Use `initial_wait: 120-300` (seconds) for `build`/`test`/`bootRun` — these
are not fast commands. `:core:compileJava` and single targeted test
classes are the quick sanity-check options while iterating.

For a real end-to-end run (real Jira/Slack/GitHub/LLM/local k8s sandbox
via minikube), follow `docs/local-testing.md` exactly — don't improvise
credentials/config paths; that doc is kept accurate against the actual
current config property names.

## Conventions to follow

- **No historical "Phase N" references.** This repo went through a v1
  cleanup removing all roadmap-phase numbering from code/docs/YAML/test
  paths (`docs/ROADMAP.md` is the one place that's allowed to reference
  historical phases). Don't reintroduce phase numbers into Javadoc,
  comments, or file/directory names — describe *what exists now*, not
  what phase built it.
- **ADRs describe only current-state architecture**, Nygard format
  (Context/Decision/Alternatives Considered/Consequences), one accepted
  decision per file, numbered sequentially in `docs/adr/`. If you make or
  discover a genuine architectural decision (a new persistence approach,
  a new pattern for agent/tool composition, a rejected alternative worth
  recording), propose a new ADR rather than only leaving the rationale in
  a code comment — but confirm with the user before creating one (don't
  auto-write ADR files unprompted).
- **Every agent/tool README documents**: the exact tool/agent contract
  (state read/written, `@Tool` methods + params, config keys), its
  collaborators/`PluginContextAware` wiring, its Gradle dependencies, and
  any non-obvious gotchas. Follow the existing `agents/*/README.md` /
  `tools/*/README.md` files as the template for any new module.
- **One class, no strategy-pattern fallback layers.** Built-in agents
  handle both the "LLM configured" and "no LLM configured" cases in the
  same class, falling back to a deterministic heuristic when
  `LlmClient#complete` returns blank (`agentic.llm.enabled=false` case) —
  don't reintroduce a separate stub/assistant class hierarchy for this.
- **Node config over hardcoding/global properties.** Anything that varies
  per workflow node (channel, max rounds, knowledge sources, repository)
  is read from `NodeDefinition#config()` at execution time — never
  hardcoded in an agent or pulled from a global
  `@ConfigurationProperties` bean.
- **`ThreadLocal` for per-turn/per-call tool state**, never a plain
  instance field — tool bundles are Spring singletons but Spring AI
  invokes `@Tool` methods synchronously on the calling agent's own
  thread, and multiple workflow turns run concurrently on different
  threads. See `workspace-setup-tool`/`ask-human-tool` for the pattern.
  Always call `ToolResults.orPlaceholder(...)` before returning a
  `String` from a `@Tool` method that could legitimately be blank (see
  `tools/tool-support/README.md` for the upstream Gemini bug this works
  around).
- **This is a plain Gradle project, not shaded/fat-jar for plugin
  modules.** `agents/*`/`tools/*` build plain jars (`./gradlew jar`), not
  Spring Boot fat jars — only `core` uses the Spring Boot Gradle plugin
  and produces a `bootJar`.

## Testing expectations

- Run the narrowest targeted build/test command that covers your change
  first (see commands above); escalate to `./gradlew build` (full, no
  `-x test`) before considering a change done if it touches shared
  contracts (`core`'s plugin API surface, `WorkflowState`, `Agent`/
  `ToolBundle` interfaces) or multiple modules.
- `agents-integration-tests` exercises the real `ServiceLoader`
  plugin-loading path across every built-in agent/tool jar together — run
  it after any change to plugin discovery, `PluginContext`, or a built-in
  agent/tool's `META-INF/services` wiring, or after renaming/moving test
  resource directories under `agents-integration-tests/src/test/resources`.
- Don't add new linting/build tooling — there is no checkstyle/spotless
  config in this repo today; match existing code style by example rather
  than introducing a formatter.

## Git

This repository has a `.git` directory but **no commits yet** — verify
`git log`/`git status` before assuming any git history conventions (there
is none to follow yet). Don't invent commit-message conventions unless
asked; ask the user how they want the first commit(s) structured if it's
relevant to your task.

## Skills

`.agents/skills/` holds repo-committed skills for CLI coding agents working
in this repo — task-specific playbooks that go deeper than this file for a
particular recurring job. Check whether one applies before starting:

- **`adding-agent-or-tool-module`** — scaffolding a new `Agent`/`ToolBundle`
  Gradle module and keeping the four places that must know about it in sync
  (`settings.gradle.kts`, the module itself, `agents-integration-tests`'
  `pluginModules` list, `Dockerfile`'s jar-build list).
- **`adding-workflow-yaml`** — the workflow YAML schema (nodes, edges,
  triggers, `knowledgeSources`), the gate-node-vs-agent-node pattern, and
  `requiresApproval`/`resumeTrigger` pause-and-resume semantics.
- **`local-e2e-test`** — running the full `jira-to-pr` workflow locally
  against real Jira/Slack/GitHub and a local Kubernetes (minikube) sandbox,
  automating `docs/local-testing.md` plus known troubleshooting gotchas.

If your CLI tool doesn't auto-discover `.agents/skills/`, read the relevant
`SKILL.md` directly before starting the matching task.

## Where to look for more detail

- `README.md` — project overview, architecture diagrams, tech stack,
  local/cloud running instructions.
- `docs/adr/README.md` — index of all accepted architecture decisions.
- `docs/plugins.md` — the plugin contract in full.
- `docs/local-testing.md` — real end-to-end local testing walkthrough.
- `agents/README.md`, `tools/README.md` — per-module agent/tool
  documentation indexes.
- `deploy/helm/java-cloud-agent-framework/README.md` — Helm chart reference.
- `plugins-template/README.md` — starting point for a separate plugin repo.
- `.agents/skills/` — task-specific playbooks (see "Skills" above).
