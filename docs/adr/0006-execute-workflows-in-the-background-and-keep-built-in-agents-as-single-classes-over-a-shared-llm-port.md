# ADR-0006: Execute workflows in the background and keep built-in agents as single classes over a shared LLM port

**Date**: 2026-07-24
**Status**: accepted
**Deciders**: @manevpe, Copilot CLI

## Context

Webhook senders expect quick HTTP responses, but some workflow nodes can take much longer: planning may do several tool calls, and coding may run real repository build/test commands. Running the full graph on the request thread would cause timeout and retry problems.

Separately, earlier iterations split behavior across Agent, Assistant, and Service layers. In the current codebase, that indirection no longer buys meaningful runtime flexibility.

## Decision

`WorkflowEngineService` submits both workflow starts and resumes to a background `ExecutorService` and returns immediately to the caller. The webhook controller remains a thin ingress that acknowledges requests quickly and leaves long-running work to the background engine.

Built-in workflow behavior lives in single agent classes:
- `PlanningAgent`
- `ConversationalPlanningAgent`
- `CodingAgent` with `implement` and `respond-to-review` modes
- simple gate/posting agents for Slack, Jira, PR review, and conversation resume

Agents always depend on one `LlmClient` port:
- `LoggingLlmClient` is the default stub when LLMs are disabled,
- `SpringAiLlmClient` is the real adapter when a provider-specific `ChatModel` is configured.

Provider choice is isolated to small configuration classes such as `GoogleGenAiAutoConfiguration` and `GitHubModelsAutoConfiguration`, each supplying a provider-agnostic `ChatModel` bean selected via `agentic.llm.provider`. Agent code stays provider-agnostic and keeps deterministic inline fallback behavior for the no-LLM case. `SpringAiLlmClient` also centralizes structured logging of every LLM call — prompt, response, call duration, token usage, and (when the configured model supports it) separated "thought summary" vs. final-answer text — in one place rather than per agent.

## Alternatives Considered

### Alternative 1: Run graph invocations synchronously on the webhook request thread
- **Pros**: Simpler control flow.
- **Cons**: Risks webhook timeouts and duplicate retries during long-running nodes.
- **Why not**: The system must return to external senders quickly.

### Alternative 2: Keep separate Assistant/Service strategy layers for every built-in agent
- **Pros**: Smaller files and more explicit layering.
- **Cons**: More indirection without real runtime swapping value in the current design.
- **Why not**: The codebase now benefits more from direct, single-place behavior.

### Alternative 3: Give each agent its own provider-specific LLM adapter
- **Pros**: Agent-local naming and wiring.
- **Cons**: Repeats the same enable/disable/provider selection logic everywhere.
- **Why not**: One shared `LlmClient` port is simpler and keeps provider logic — including logging and retry visibility — centralized.

## Consequences

### Positive
- Webhook ingress stays responsive.
- Agent behavior is easier to trace because each capability lives in one class.
- LLM integration remains provider-agnostic, test-friendly, and consistently logged across every agent.

### Negative
- Some agent classes are larger than before.
- In-flight background work is not fully restart-transparent at the granularity of an unfinished tool-calling turn.

### Risks
- Prompt/tool protocol drift can break parsing or expected tool submission.
  - **Mitigation**: keep agent-local parsing strict and keep targeted tests around tool-calling behavior.
- Background failures are no longer visible to callers via synchronous exceptions.
  - **Mitigation**: log and audit execution failures explicitly, including retry attempts (e.g. `GoogleGenAiAutoConfiguration`'s quota-retry listener).
