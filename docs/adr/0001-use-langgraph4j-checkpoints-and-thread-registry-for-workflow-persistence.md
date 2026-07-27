# ADR-0001: Use LangGraph4j checkpoints and a thread registry for workflow persistence

**Date**: 2026-07-24
**Status**: accepted
**Deciders**: @manevpe, Copilot CLI

## Context

The framework executes long-running workflows that can pause for Slack replies, GitHub review events, or future approval decisions. That requires durable state that survives process restarts and lets execution resume from the correct node with the correct business state.

LangGraph4j already defines checkpoint persistence as part of its execution model. The framework also needs one small piece of extra metadata that LangGraph4j checkpoints do not carry themselves: which workflow definition a given `threadId` belongs to.

## Decision

We use LangGraph4j's checkpoint saver SPI as the sole source of truth for workflow execution state. `JpaCheckpointSaver` persists checkpoints in Postgres, and every workflow start or resume runs against the same `threadId` so LangGraph4j reloads the saved checkpoint state.

We keep a separate `workflow_thread` registry only to map `threadId` to `workflowId`, because checkpoints do not record which YAML workflow produced them. We do not maintain a second bespoke workflow-instance state machine alongside LangGraph4j.

`audit_log` and `pending_action` remain supporting operational tables, not competing workflow-state sources.

## Alternatives Considered

### Alternative 1: Keep a bespoke workflow-instance table as the primary state model
- **Pros**: Full control over schema shape and query model.
- **Cons**: Duplicates LangGraph4j's own execution state; easy for the two models to drift.
- **Why not**: The framework already depends on LangGraph4j for pause/resume semantics, so duplicating its state model would add complexity without adding correctness.

### Alternative 2: Keep both a bespoke state table and LangGraph4j checkpoints
- **Pros**: Easier dashboards and ad hoc queries against a simplified table.
- **Cons**: Two sources of truth for workflow position and state; every transition must update both.
- **Why not**: Operational convenience is not worth the synchronization risk.

## Consequences

### Positive
- One durable source of truth for workflow progress.
- Native compatibility with LangGraph4j's resume behavior.
- Less custom persistence logic to maintain.

### Negative
- The persistence format is more tightly coupled to LangGraph4j's checkpoint model.
- Debugging stored state requires understanding LangGraph4j checkpoint structure.

### Risks
- Checkpoints alone cannot identify the owning workflow definition.
  - **Mitigation**: keep the separate `workflow_thread` registry.
- Future workflow-engine replacement would require a new persistence adapter.
  - **Mitigation**: keep the thread registry, audit log, and pending-action tables narrow and engine-agnostic where possible.
