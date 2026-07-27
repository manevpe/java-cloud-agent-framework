# ADR-0002: Pause workflows with `interruptsAfter` and resume them with `GraphInput.resume`

**Date**: 2026-07-24
**Status**: accepted
**Deciders**: @manevpe, Copilot CLI

## Context

Workflow nodes may need to stop after executing and wait for an external event or an approval decision. Resume must continue from the correct post-node edge with the saved checkpoint state plus the newly arrived event payload.

Earlier "custom resume" ideas conflict with LangGraph4j's actual execution model. The framework therefore needs one pause/resume design that matches library behavior exactly and keeps routing deterministic.

## Decision

We model pause points declaratively in workflow YAML. Any node with `resumeTrigger` or `requiresApproval: true` is compiled under LangGraph4j's native `CompileConfig.interruptsAfter(...)`.

At runtime:
- the agent returns `WaitForEvent` or `WaitForApproval`,
- `WorkflowNodeAction` records waiting status and correlation key in workflow state,
- `WorkflowEngineService` stores a `PendingAction`,
- resume happens through `CompiledGraph.invoke(GraphInput.resume(payload), config)` using the same `threadId`.

We also compile graphs with `interruptBeforeEdge(true)`, so routing-edge evaluation happens after resume data has been merged into state.

`WorkflowNodeAction` enforces the contract in both directions:
- interrupt-eligible nodes must not return `Continue`,
- non-interrupt nodes must not return `WaitForEvent` or `WaitForApproval`.

## Alternatives Considered

### Alternative 1: Route waiting nodes to `END` and manually target the next node on resume
- **Pros**: Looks simple at the workflow level.
- **Cons**: Fights LangGraph4j's real resume semantics and risks restarting or terminating the run incorrectly.
- **Why not**: The library already provides a correct checkpoint-aware resume path.

### Alternative 2: Re-enter the same node on resume with a self-loop
- **Pros**: Intuitive mental model for "run this node again once input arrives".
- **Cons**: Literal self-loops do not fit the framework's interrupt behavior and make routing/pause semantics harder to reason about.
- **Why not**: The framework instead models repeated interactions as explicit gate-node loops or conversation sessions.

## Consequences

### Positive
- Pause/resume follows LangGraph4j's native contract.
- Resume payload is available before deferred edge evaluation.
- Invalid pause-node configurations fail fast instead of hanging silently.

### Negative
- Pause points are declared up front in workflow definitions, not chosen ad hoc inside a node.
- Repeated interactions require explicit workflow structure or the separate conversation-session model.

### Risks
- Library behavior could change across LangGraph4j upgrades.
  - **Mitigation**: keep pause/resume integration tests around the real compiled-graph path.
- Workflow authors may misdeclare pause semantics in YAML.
  - **Mitigation**: `WorkflowNodeAction` validates agent result vs. interrupt eligibility at runtime.
