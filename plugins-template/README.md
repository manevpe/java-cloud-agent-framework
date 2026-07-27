# Plugin templates

`java-cloud-agent-framework` loads custom `Agent`, `EdgeCondition` and `Skill`
implementations from external jars via the JDK's own `ServiceLoader`
mechanism (see `PluginManager`, `PluginProperties`) — no dependency from
this repo onto your plugin repo, and no dependency from your plugin repo
onto this repo's build beyond its public API packages.

This directory contains three minimal, standalone template modules to copy
into your own repository as a starting point:

| Template | Demonstrates |
|---|---|
| `agent-template/` | A custom `Agent` (workflow node) + a custom `EdgeCondition` (routing predicate) |
| `tool-template/` | A custom `ToolBundle` — a named, reusable set of LLM tools (no prompt opinion), pulled in by an agent's `requiredTools()` or a workflow YAML node's `tools:` list |
| `skill-template/` | A custom `Skill` (reusable prompt fragment + tool bundle) built on a plain Spring AI `ToolCallback` |

## How plugin loading works

1. Implement one or more of `io.github.manevpe.agentic.agent.Agent`,
   `io.github.manevpe.agentic.engine.EdgeCondition`,
   `io.github.manevpe.agentic.tool.ToolBundle`, or
   `io.github.manevpe.agentic.skill.Skill`.
2. Declare each implementation under `META-INF/services/<fully-qualified-interface-name>`
   in your jar, one implementing class per line — standard `ServiceLoader`
   convention. See each template's
   `src/main/resources/META-INF/services/` file for the exact format.
3. Build a plain jar (`./gradlew jar` in your plugin module — no shading,
   no Spring Boot fat-jar needed; the framework's own classes are on the
   classpath already at runtime).
4. Drop the jar into the directory configured as `agentic.plugins.directory`
   (see `application.yml`) and (re)start the app. On startup,
   `PluginManager` scans that directory (non-recursive), builds one
   `URLClassLoader` across every `*.jar` found there, and uses
   `ServiceLoader.load(...)` to discover your implementations.
5. Reference your agent by its `type()` id from workflow YAML
   (`node.agent: my-custom-agent`), your condition by its class's
   decapitalized simple name from an edge's `condition:` field (e.g. class
   `MyCondition` → `myCondition`), your tool bundle by its `name()` from an
   agent's `Agent#requiredTools()` or a workflow node's own `tools:` list,
   and your skill by its `name()` from a conversational agent's `skills:`
   list in YAML.

## Constraints to know

- **All plugin jars share one flat classpath** (one `URLClassLoader` for
  the whole directory, not one per jar) — fine for a handful of
  organization-authored plugins, but two plugin jars must not declare
  clashing package-private class names in the same package.
- **`type()`/skill `name()`/tool bundle `name()` must be globally unique**
  across built-in and plugin-provided agents/skills/tools — a duplicate
  throws a startup error (`IllegalStateException`) rather than silently
  shadowing one.
- **Disabling a built-in**: set `agentic.agents.disabled-types: [some-builtin-type]`
  in your own `application.yml` — this also applies to plugin-provided
  agents, in case you need to disable one without removing its jar.
- If `agentic.plugins.directory` is unset, missing, or contains no jars,
  plugin loading is a no-op — zero startup cost for deployments that don't
  use it.

## Building a template locally

Each template's `build.gradle.kts` compiles against the framework's own
jar. Point `frameworkJar` at your built `java-cloud-agent-framework-*.jar` (or a
published artifact once this project ships one), e.g.:

```
./gradlew :plugins-template:agent-template:jar -PframeworkJar=/path/to/java-cloud-agent-framework-0.1.0-SNAPSHOT.jar
```
