# ADR-0008: Use `ToolBundle` and `ToolRegistry` for reusable LLM tools

**Date**: 2026-07-24
**Status**: accepted
**Deciders**: @manevpe, Copilot CLI

## Context

Several agents need overlapping LLM tools. Some tools are broadly reusable across agents, while others are private sentinel tools used only to collect one agent's structured completion result. The framework needs a first-class way to discover, name, and reuse the shareable tools without conflating them with skills or hardcoding them inside each agent.

## Decision

Reusable LLM tools are packaged as `ToolBundle` plugins and resolved through `ToolRegistry`.

Agents declare mandatory tool bundles via `Agent.requiredTools()`. During plugin-context initialization they resolve:
- tool callbacks for LLM calls,
- typed bundle instances when they need direct non-`@Tool` access.

Current shareable built-in bundles are:
- `workspace-setup`
- `file-read`
- `file-edit`
- `ask-human`
- `github-api`
- `http-request`

Agent-private completion tools remain private implementation details. `CodingAgent` still constructs its own submit-result tools directly because those are not reusable framework capabilities.

## Alternatives Considered

### Alternative 1: Rely only on compile-time module dependencies
- **Pros**: Simpler static dependency graph.
- **Cons**: No runtime tool discovery contract and less explicit agent/tool binding inside the framework.
- **Why not**: The plugin architecture already resolves runtime capabilities by name.

### Alternative 2: Reuse `Skill` instead of defining `ToolBundle`
- **Pros**: One fewer interface.
- **Cons**: Skills carry prompt semantics; reusable tools do not necessarily do so.
- **Why not**: Tool reuse and prompt composition are different concerns.

### Alternative 3: Force private sentinel tools through the same shared registry
- **Pros**: Uniformity.
- **Cons**: Exposes non-reusable, agent-specific completion mechanics as if they were general-purpose tools.
- **Why not**: The extra indirection adds no value.

## Consequences

### Positive
- Tool reuse is explicit and fail-fast.
- Agents advertise their mandatory runtime tool dependencies clearly.
- New reusable tools can be added as plugins without changing core registries.

### Negative
- The framework has one more plugin concept to explain.
- Runtime deployment must include the tool jars an agent depends on.

### Risks
- A missing tool jar becomes a startup/runtime integration error rather than a compile-only concern.
  - **Mitigation**: resolve required bundle names explicitly and fail fast.
- Agents can become too dependent on name-based runtime composition if tool contracts are allowed to drift.
  - **Mitigation**: keep tool names stable and tool responsibilities narrow.
