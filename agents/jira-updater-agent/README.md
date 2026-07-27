# jira-updater-agent

`Agent#type()`: **`jira-updater-agent`**

Posts the finalized plan back to the originating Jira ticket.

## Workflow role

```yaml
# Posting a plan comment is low-risk/reversible, so it auto-executes
# instead of pausing for approval — see ADR-0004.
- id: post-plan
  agent: jira-updater-agent
```

Runs immediately after planning/clarification completes (either
`planning-agent` + `human-gate`, or `conversational-planning-agent` +
`conversation-resume-gate`), before routing on to `coding-agent`'s
`implement` mode.

## Behavior

Reads `finalPlan` (falling back to `plan` if not set) from workflow state
and posts it as a comment on the Jira ticket via `JiraClient#postComment`.
The plan text is already final by the time this runs —
`planning-agent`/`conversational-planning-agent` incorporate every
clarification round directly into `plan`/`finalPlan` as they redraft, so
this agent never needs to concatenate a raw Slack/human answer onto it
itself.

Executes immediately rather than pausing for approval — a Jira comment is
low-risk and reversible (see ADR-0004's auto-execute-low-risk-side-effects
rationale).

## State read/written

| Key | Read | Written |
|---|---|---|
| `ticketKey` | ✓ | |
| `finalPlan` (or `plan` as fallback) | ✓ | ✓ (`finalPlan`, normalized) |

## Configuration (`node.config`)

None — this node has no configurable behavior of its own.

## Collaborators

Implements `PluginContextAware`. `setPluginContext` wires in
`context.jiraClient()`.

## Dependencies

No tool dependencies — this agent only integrates with a deterministic
REST client port from `:core` (`JiraClient`).

```kotlin
dependencies {
    compileOnly(project(":core"))

    testImplementation(project(":core"))
}
```
