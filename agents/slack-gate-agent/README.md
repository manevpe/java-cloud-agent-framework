# slack-gate-agent

`Agent#type()`: **`human-gate`**

Starts a new Slack thread asking the team to clarify a plan's open
questions — a "grill-me"-style human gate — and pauses the workflow until
someone replies.

## Workflow role

```yaml
- id: await-clarifications
  agent: human-gate
  config:
    channel: "#agentic_cloud_test"   # default: "#dev-agent-plans"
  resumeTrigger:
    type: event
    source: slack
    event: thread_reply
```

Only ever routed to when [`planning-agent`](../planning-agent/README.md)'s
plan has open questions (the `hasOpenQuestions` edge condition), so it
unconditionally pauses — it never needs to decide whether to wait. Once a
developer replies in the Slack thread, the `thread_reply` webhook event
resumes the workflow, which loops back into `plan`.

## Behavior

1. Reads `channel` from node config (default `#dev-agent-plans`),
   `ticketKey` and `openQuestions` from workflow state.
2. Posts a single Slack message listing the open questions via
   `SlackClient#postThread`.
3. Stores the returned `slackThreadId` in state and returns
   `AgentResult.WaitForEvent(state, threadId)` — the correlation key a
   later `POST /webhooks/resume/{correlationKey}` (triggered by the
   `thread_reply` event) resumes this paused node with.

## State read/written

| Key | Read | Written |
|---|---|---|
| `ticketKey`, `openQuestions` | ✓ | |
| `slackThreadId` | | ✓ |

## Configuration (`node.config`)

| Key | Default | Meaning |
|---|---|---|
| `channel` | `#dev-agent-plans` | Slack channel the clarification thread is posted to |

## Collaborators

Implements `PluginContextAware`. `setPluginContext` wires in
`context.slackClient()`.

## Dependencies

No tool dependencies — this agent only integrates with a deterministic
REST client port from `:core` (`SlackClient`).

```kotlin
dependencies {
    compileOnly(project(":core"))

    testImplementation(project(":core"))
}
```
