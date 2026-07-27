# Plugins: custom agents, edge conditions, and skills

`java-cloud-agent-framework` supports loading custom `Agent`, `EdgeCondition`,
and `Skill` implementations from external jars at startup, without those
implementations ever being on this repo's own build classpath. This is how
teams add org-specific workflow steps, routing logic, or LLM tool bundles
without forking the framework.

## Configuration

```yaml
agentic:
  plugins:
    directory: /opt/agentic/plugins   # unset/absent = plugin loading disabled entirely
  agents:
    disabled-types: []                # e.g. [pr-comment-gate] to turn off a built-in agent
```

- `agentic.plugins.directory` — a directory scanned **non-recursively** for
  `*.jar` files at application startup. If unset, missing, or empty of
  jars, plugin loading is a complete no-op (zero startup cost).
- `agentic.agents.disabled-types` — a list of `Agent#type()` ids to
  exclude from the registry, regardless of whether they're built-in or
  plugin-provided. Lets an operator turn off a capability via config
  rather than removing code or a jar.

## How discovery works

`PluginManager` builds a single `URLClassLoader` across every jar found in
the configured directory (parent = the framework's own classloader, so
plugin code can reference framework API types), then uses the JDK's
`ServiceLoader` to discover implementations of:

- `io.github.manevpe.agentic.agent.Agent`
- `io.github.manevpe.agentic.engine.EdgeCondition`
- `io.github.manevpe.agentic.skill.Skill`

Each plugin jar declares its implementations the standard `ServiceLoader`
way: a file at `META-INF/services/<fully-qualified-interface-name>`
containing one implementing class's fully-qualified name per line.

`AgentRegistry` and `ConditionRegistry` then merge these plugin-discovered
instances with built-in, Spring-bean-registered ones into a single
registry, throwing a startup error (`IllegalStateException`) on any name
collision — a plugin can extend the framework, but never silently shadow a
built-in.

## Registering plugin-provided items in workflow YAML

| Type | Registered under | Referenced from YAML as |
|---|---|---|
| `Agent` | its own `type()` | `node.agent: <type>` |
| `EdgeCondition` | its class's simple name, decapitalized (no bean name to borrow) — e.g. class `MyCondition` → `myCondition` | `edge.condition: <name>` |
| `Skill` | its own `name()` | a conversational agent's `skills: [<name>, ...]` list |

## Constraints

- **Shared flat classpath**: every plugin jar in the directory joins one
  `URLClassLoader`, not one per jar. Fine for a handful of
  organization-authored plugins; two plugin jars must not declare
  clashing package-private class names in the same Java package.
- **Public class + public no-arg constructor required**: `ServiceLoader`
  requires the provider class itself to be `public`, with a `public`
  no-arg constructor — a package-private class (even with a technically
  public-looking constructor declaration) fails at load time with
  `ServiceConfigurationError: Unable to get public no-arg constructor`.
- **Global uniqueness**: `type()`/`name()` must be unique across built-in
  and plugin-provided agents/skills; duplicates fail startup rather than
  silently overriding one another.

## Getting real collaborators via PluginContext (ADR-0007)

A no-arg constructor means a plugin can't receive dependencies through
Spring the normal way. If a plugin needs one of the framework's own
collaborators (the LLM client, Jira/GitHub/Slack clients, the sandbox
workspace client, the human-interaction registry, or the conversation
session repository), implement `io.github.manevpe.agentic.plugin.PluginContextAware`
in addition to `Agent`/`EdgeCondition`/`Skill`:

```java
public class MyAgent implements Agent, PluginContextAware {

    private PluginContext context;

    @Override
    public void setPluginContext(PluginContext context) {
        this.context = context;
    }

    @Override
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        String reply = context.llmClient().complete(systemPrompt, userPrompt);
        // ...
    }
}
```

`PluginManager` calls `setPluginContext(...)` immediately after
`ServiceLoader` instantiates your class, before it's registered — every
method on `PluginContext` returns the exact same Spring-managed singleton
a built-in agent would get via constructor injection (e.g. `llmClient()`
carries this application's tool-exception-handling configuration; a
plugin should never construct its own `ChatClient`/`LlmClient`). Methods
you don't call are never a problem: every one defaults to throwing
`UnsupportedOperationException`, so a plugin that only needs
`jiraClient()` doesn't have to stub out the rest even in its own unit
tests (`new PluginContext() {}` is a valid no-op double).
`PluginContext#resourceLoader()` resolves `classpath:`/`file:` locations
against *your plugin's own jar*, so a bundled prompt template like
`classpath:my-prompt.st` resolves correctly even though the framework's
own classpath doesn't contain it.

## Plugin API surface

The classes below are the framework's stable, load-bearing plugin
contract — the same interfaces/records this repository's own
out-of-the-box agents (the `agents/*` and `tools/*` modules, one per
agent/tool — see ADR-0009) compile against, nothing more:

- `io.github.manevpe.agentic.agent.Agent`, `AgentResult`
- `io.github.manevpe.agentic.engine.EdgeCondition`
- `io.github.manevpe.agentic.skill.Skill`
- `io.github.manevpe.agentic.tool.ToolBundle`, `ToolRegistry`
- `io.github.manevpe.agentic.workflow.WorkflowState`, `NodeDefinition`, `TriggerDefinition`
- `io.github.manevpe.agentic.plugin.PluginContext`, `PluginContextAware`
- `io.github.manevpe.agentic.integration.LlmClient`, `JiraClient`, `GitHubClient`,
  `SlackClient`, `SandboxWorkspaceClient`, `HumanInteractionClientRegistry`, `HumanInteractionClient`
- `io.github.manevpe.agentic.persistence.ConversationSessionRepository`
- `io.github.manevpe.agentic.conversation.ConversationSession`

Everything else under `io.github.manevpe.agentic.*` (engine internals,
Spring `@Configuration` classes, JPA entities, the LangGraph4j wiring,
...) is an implementation detail the framework reserves the right to
change without notice — a plugin depending on anything outside this list
is depending on an unstable internal API. See ADR-0007/ADR-0009 for the
rationale; a future revision may extract this list into its own published
`java-cloud-agent-framework-api` artifact, but today it's enforced by
convention (this list) rather than by a separate Gradle module/jar.

## Getting started

See `plugins-template/` at the repo root for three copy-and-adapt template
modules (`agent-template/` for a custom `Agent` + `EdgeCondition`,
`tool-template/` for a custom `ToolBundle` — a named, reusable set of LLM
tools with no prompt opinion, `skill-template/` for a custom `Skill`
bundling a Spring AI `@Tool`-annotated tool with a prompt fragment), plus
a fuller walkthrough in that directory's own `README.md`. The `agents/`
and `tools/` modules in this repo (one Gradle module/jar per built-in
agent or shareable tool — ADR-0009) are themselves a full, real-world
example of the same plugin contract, worth reading alongside the
templates.

## Deployment: bare vs. `-with-default-modules` image

The `Dockerfile` builds two runtime targets from the same build stage:

- `runtime` (the default/bare image) — zero built-in agents, an empty
  `/opt/agentic/plugins`. Bring your own agent/tool jars (see the Helm
  chart's `README.md` "Supplying plugin jars" section for both the
  derived-image and externally-supplied-jar approaches).
- `runtime-with-default-modules` — the same bare image, plus every
  out-of-the-box agent/tool jar from this repo's `agents/*`/`tools/*`
  modules pre-copied into `/opt/agentic/plugins`. Build it with:
  ```bash
  docker build --target runtime-with-default-modules \
    -t java-cloud-agent-framework:X.Y.Z-with-default-modules .
  ```

Both targets share one `build` stage (`docker build` only executes it
once regardless of which `--target` you pick, or if you build both), so
there's no extra compile cost for offering the convenience variant.

