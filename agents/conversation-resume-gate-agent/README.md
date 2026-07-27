# conversation-resume-gate-agent

`Agent#type()`: **`conversation-resume-gate`**

The actual pause point for any conversation-session-based agent (e.g.
[`conversational-planning-agent`](../conversational-planning-agent/README.md))
— a trivial, reusable node that always pauses on whatever correlation key
the owning agent left in state, then loops back into that same agent once
the human's reply resumes it.

## Workflow role

```yaml
- id: conversation-resume-gate
  agent: conversation-resume-gate
  resumeTrigger:
    type: event
    source: slack
    event: thread_reply
```

This mirrors the split already used by `human-gate`/`pr-comment-gate`: per
the LangGraph4j constraint documented on `WorkflowGraphFactory`, a node
can't both be an `interruptsAfter` point and conditionally continue, so
the "does the real work" agent (which decides whether to pause) and "is
the actual pause point" node must be separate. Unlike those two, this
single gate node is **reused across every conversation-session-based
agent**, since its job is identical regardless of which agent is driving
the conversation: read `humanQuestionCorrelationKey` from state and wait.

## Behavior

Reads `humanQuestionCorrelationKey` from state (set by the owning
conversational agent right before routing here — throws
`IllegalStateException` if missing, since reaching this node without one
would be a routing bug) and returns
`AgentResult.WaitForEvent(state, correlationKey)`.

## State read/written

| Key | Read | Written |
|---|---|---|
| `humanQuestionCorrelationKey` | ✓ | |

## Configuration (`node.config`)

None — this node has no configurable behavior of its own.

## Collaborators

None — no `PluginContextAware` wiring; this agent has no framework
collaborators to receive.

## Dependencies

No tool dependencies — a pure gate/routing node.

```kotlin
dependencies {
    compileOnly(project(":core"))

    testImplementation(project(":core"))
}
```
