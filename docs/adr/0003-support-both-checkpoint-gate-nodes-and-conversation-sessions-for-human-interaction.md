# ADR-0003: Support both checkpoint gate nodes and conversation sessions for human interaction

**Date**: 2026-07-24
**Status**: accepted
**Deciders**: @manevpe, Copilot CLI

## Context

The framework has two different human-interaction needs.

Some pauses are simple workflow checkpoints: ask in Slack, wait for a reply, then continue the graph. Others are genuinely conversational: the agent may need several follow-up questions, with each answer replayed into the same running context before the agent can finalize its result.

A single mechanism does not fit both well.

## Decision

We support two human-interaction models side by side.

1. **Checkpoint gate-node model**
   - Used for workflow-level orchestration such as `human-gate`, `pr-comment-gate`, and `conversation-resume-gate`.
   - The graph pauses at explicit nodes and resumes by correlation key through checkpointed workflow state.

2. **Conversation-session model**
   - Used when an agent itself needs a durable multi-turn conversation.
   - `ConversationalPlanningAgent` persists a `ConversationSession`, calls `ask-human`, and resumes the conversation with full transcript replay until it completes or reaches its round limit.

Messaging-provider selection is per-node and resolved through `HumanInteractionClientRegistry`, so the interaction mechanism is not hardcoded to Slack even though Slack is the current built-in provider.

## Alternatives Considered

### Alternative 1: Use only checkpoint gate nodes
- **Pros**: One pause/resume mechanism for the whole system.
- **Cons**: Multi-round clarification becomes awkward and rigid because every possible round must be expressed in graph structure.
- **Why not**: Within-node conversations are a real requirement.

### Alternative 2: Replace all gate nodes with conversation sessions
- **Pros**: One conceptual model for every human interaction.
- **Cons**: Overcomplicates simple workflow orchestration pauses such as PR review waiting.
- **Why not**: Graph-level control flow and within-node conversation are different problems.

## Consequences

### Positive
- Workflow authors can choose the right interaction model per node.
- Multi-turn conversational agents can replay their own durable transcript.
- Simple graph checkpoints remain simple.

### Negative
- The framework now has two pause/resume concepts to explain.
- Conversation state and workflow checkpoint state are correlated loosely rather than merged into one model.

### Risks
- The two models could drift in operator expectations or safety limits.
  - **Mitigation**: keep their configuration explicit and keep round limits on both models.
- Future messaging providers must match the same correlation-key resume contract.
  - **Mitigation**: resolve providers through `HumanInteractionClientRegistry` behind one interface.
