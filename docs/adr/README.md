# Architecture Decision Records

This is a clean, v1-release view of the framework's architecture decisions:
each ADR below describes only the **current, final** state of that decision.
Superseded/rejected earlier approaches are summarized as "Alternatives
Considered" inside the ADR that replaced them, not as separate files.

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [0001](0001-use-langgraph4j-checkpoints-and-thread-registry-for-workflow-persistence.md) | Use LangGraph4j checkpoints and a thread registry for workflow persistence | accepted | 2026-07-24 |
| [0002](0002-pause-workflows-with-interruptsafter-and-resume-with-graphinput-resume.md) | Pause workflows with `interruptsAfter` and resume them with `GraphInput.resume` | accepted | 2026-07-24 |
| [0003](0003-support-both-checkpoint-gate-nodes-and-conversation-sessions-for-human-interaction.md) | Support both checkpoint gate nodes and conversation sessions for human interaction | accepted | 2026-07-24 |
| [0004](0004-auto-execute-low-risk-side-effects-and-use-pr-review-as-the-code-change-checkpoint.md) | Auto-execute low-risk side effects and use PR review as the code-change checkpoint | accepted | 2026-07-24 |
| [0005](0005-use-synchronous-sandbox-workspaces-for-repository-exploration-editing-and-build-test-execution.md) | Use synchronous sandbox workspaces for repository exploration, editing, and build/test execution | accepted | 2026-07-24 |
| [0006](0006-execute-workflows-in-the-background-and-keep-built-in-agents-as-single-classes-over-a-shared-llm-port.md) | Execute workflows in the background and keep built-in agents as single classes over a shared LLM port | accepted | 2026-07-24 |
| [0007](0007-load-agents-conditions-skills-and-tool-bundles-as-serviceloader-plugins-with-plugincontext-injection.md) | Load agents, conditions, skills, and tool bundles as ServiceLoader plugins with PluginContext injection | accepted | 2026-07-24 |
| [0008](0008-use-toolbundle-and-toolregistry-for-reusable-llm-tools.md) | Use `ToolBundle` and `ToolRegistry` for reusable LLM tools | accepted | 2026-07-24 |
| [0009](0009-package-the-framework-as-a-bare-core-runtime-plus-one-plugin-jar-per-built-in-agent-and-shareable-tool.md) | Package the framework as a bare core runtime plus one plugin jar per built-in agent and shareable tool | accepted | 2026-07-24 |
