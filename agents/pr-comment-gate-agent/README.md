# pr-comment-gate-agent

`Agent#type()`: **`pr-comment-gate`**

Pauses the workflow until the next relevant GitHub event on the PR
arrives — a submitted review (which may bundle several individual line
comments into one event), or the PR being merged/closed.

## Workflow role

```yaml
- id: pr-feedback-loop
  agent: pr-comment-gate
  resumeTrigger:
    type: event
    source: github
    # A single webhook target handles both a submitted review (batching
    # every comment left in it) and the PR being merged/closed.
    event: pr_review_submitted_or_closed
```

Loops with [`coding-agent`](../coding-agent/README.md)'s
`respond-to-review` mode (`canContinuePrFeedbackLoop` condition) — once
per review cycle, up to that node's own `maxIterations`, or until the PR
is merged/closed.

## Behavior

Derives a correlation key deterministically from `ticketKey` (`"pr-comment:"
+ ticketKey`) and returns `AgentResult.WaitForEvent(state, correlationKey)`.

### Why key off `ticketKey`, not the PR URL

The correlation key can't be the PR URL, since PR URLs typically contain
slashes that would break routing on `/webhooks/resume/{correlationKey}` (a
single path segment). Keying off `ticketKey` instead means **one GitHub
webhook subscription** (configured once against the repository, alongside
whatever other webhooks it already has) keeps resuming this same thread
across however many review/merge/close events arrive, via an adapter that
maps `prUrl -> ticketKey -> correlationKey` and calls the generic `POST
/webhooks/resume/{correlationKey}` endpoint.

### Why key off `pull_request_review` "submitted", not per-comment events

Keying off GitHub's `pull_request_review` "submitted" event (rather than
the finer-grained `pull_request_review_comment` event) is what lets
`coding-agent`'s `respond-to-review` mode batch every comment from one
review into a single amending commit instead of pushing a separate commit
per line comment.

The resumed payload's `prEventType` field (`review_submitted`, `merged`,
or `closed`) tells `coding-agent` which kind of event this was.

## State read/written

| Key | Read | Written |
|---|---|---|
| `ticketKey` | ✓ | |

(`prEventType` is written by the resume payload itself, consumed by
`coding-agent`'s `respond-to-review` mode — not by this agent.)

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
