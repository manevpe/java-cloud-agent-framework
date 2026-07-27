# ADR-0009: Package the framework as a bare core runtime plus one plugin jar per built-in agent and shareable tool

**Date**: 2026-07-24
**Status**: accepted
**Deciders**: @manevpe, Copilot CLI

## Context

The runtime should be deployable as a bare framework with no opinionated built-in agents compiled into the application itself. At the same time, the project's own built-in capabilities should be selectively deployable, independently versionable, and dogfood the same plugin API exposed to downstream teams.

## Decision

The Gradle build is organized as:
- **`core`**: the Spring Boot runtime, workflow engine, persistence, integration ports, plugin manager, registries, and shared APIs.
- **one module per built-in agent** under `agents/`
- **one module per shareable tool** under `tools/`
- **`tools:tool-support`** as a shared helper library
- **`agents-integration-tests`** as the end-to-end plugin-aggregation test module

Each built-in agent and each shareable tool produces its own jar and is loaded by `core` from the plugins directory through the same `ServiceLoader` mechanism used in production.

The current built-in plugin set is:
- agents: planning, conversational planning, coding, Jira updater, Slack gate, PR comment gate, conversation resume gate
- shareable tools: workspace setup, file read, file edit, ask human, GitHub API, HTTP request

`agents-integration-tests` aggregates the sibling jars into one flat directory because production plugin loading scans a flat jar directory.

## Alternatives Considered

### Alternative 1: Keep built-ins inside `core`
- **Pros**: Fewer modules and simpler local wiring.
- **Cons**: Core would no longer be a genuinely bare plugin host.
- **Why not**: The framework should dogfood its own plugin model.

### Alternative 2: Keep one `ootb-agents` jar for all built-ins
- **Pros**: Smaller module count than the current layout.
- **Cons**: Coarse deployment and versioning boundary; hides real dependency differences between agents and tools.
- **Why not**: The current architecture benefits from finer-grained packaging.

### Alternative 3: One jar per agent, but duplicate shared tools inside each agent jar
- **Pros**: Simpler deployment graph than separate tool jars.
- **Cons**: Reintroduces duplication and makes shared tool maintenance harder.
- **Why not**: Reusable tools are a first-class part of the runtime model.

## Consequences

### Positive
- The deployable core runtime has zero built-in agents baked in.
- Teams can deploy only the built-in agents and tools they actually want.
- Packaging reflects real capability boundaries and supports independent evolution.

### Negative
- The module graph is larger and build/deployment assembly is more involved.
- Production packaging must gather multiple jars into one plugin directory.

### Risks
- A deployment can include an agent jar but forget one of its required tool jars.
  - **Mitigation**: keep `requiredTools()` explicit and use integration tests that load jars the same way production does.
- Shared Gradle conventions affect many plugin modules at once.
  - **Mitigation**: keep common build logic centralized but small.
