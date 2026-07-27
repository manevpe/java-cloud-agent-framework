# ADR-0007: Load agents, conditions, skills, and tool bundles as ServiceLoader plugins with PluginContext injection

**Date**: 2026-07-24
**Status**: accepted
**Deciders**: @manevpe, Copilot CLI

## Context

The framework runtime is intended to ship as a reusable core that can load built-in or organization-specific capabilities from plugin jars. Those plugin implementations must be discoverable at runtime without being compiled into the Spring Boot application itself.

At the same time, plugin code still needs access to shared application services such as the LLM client, Jira/GitHub/Slack ports, sandbox client, conversation persistence, configuration, and resource loading.

## Decision

We load agents, edge conditions, skills, and tool bundles from a configured plugin directory using the JDK's `ServiceLoader` mechanism over a single flat `URLClassLoader`.

Any plugin that needs framework services implements `PluginContextAware`. `PluginManager` injects a curated `PluginContext` into those instances immediately after loading them and before registering them. The context exposes shared ports and environment/resource access, including a plugin-classloader-aware `ResourceLoader` so `classpath:` prompt files resolve from the plugin jar itself.

Built-in out-of-the-box agents and tools use the same plugin-loading path as third-party plugins. Core owns the registries and runtime; plugins own the capability implementations.

## Alternatives Considered

### Alternative 1: Dynamically register plugin classes as Spring beans
- **Pros**: Native Spring DI inside plugin implementations.
- **Cons**: Heavier runtime machinery and tighter coupling to Spring container internals.
- **Why not**: The plugin problem is simpler than "full bean lifecycle for arbitrary jars".

### Alternative 2: Make each plugin construct its own collaborators
- **Pros**: Minimal framework surface area.
- **Cons**: Duplicates client setup and risks bypassing framework-specific wiring such as the configured `ChatClient.Builder`.
- **Why not**: Plugins should reuse the application's configured singletons.

### Alternative 3: One classloader per plugin jar
- **Pros**: Stronger dependency isolation.
- **Cons**: More complex class-loading and poorer cross-plugin interoperability.
- **Why not**: The current trust model assumes a small set of coordinated plugin jars.

## Consequences

### Positive
- Core remains a real plugin host rather than a special-cased built-in bundle.
- Plugin authors get a simple JDK-standard discovery mechanism.
- Plugin code can still reuse application-managed ports and resources.

### Negative
- Plugin authors must follow both `ServiceLoader` and `PluginContextAware` conventions when they need DI.
- Plugins share one flat plugin classpath.

### Risks
- Plugin jar incompatibilities surface at load time, not compile time.
  - **Mitigation**: keep the plugin API small and stable.
- `PluginContext` exposes powerful application services and configuration.
  - **Mitigation**: treat plugins as trusted deployment artifacts, not an untrusted extension sandbox.
