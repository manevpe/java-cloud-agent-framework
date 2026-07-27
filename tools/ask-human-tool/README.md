# ask-human-tool

Bundle name: **`ask-human`**

Lets an LLM pause its own turn to ask a human a clarifying question,
rather than being confined to a single request/response exchange — this
is the tool a `ConversationSession`-based agent (see
`conversational-planning-agent`) uses instead of the fixed one-round
`human-gate`/`SlackGateAgent` node. See ADR-0003 for the broader
dual-human-interaction-model rationale (fixed gate node vs. conversation
session).

## Tools exposed

| Tool | Parameters | Returns |
|---|---|---|
| `askHuman` | `question` (the clarifying question to ask) | A fixed instruction string telling the model to stop producing anything further this turn |

## How it works

A tool call can't literally block the calling thread waiting for a reply
that might arrive minutes or days later — that would pin a virtual thread
and, worse, hold the whole workflow invocation open past any sane HTTP
timeout. Instead:

1. Calling `askHuman` starts the question/thread via
   `HumanInteractionClientRegistry` and records it in a `ThreadLocal`
   (same per-call tracking pattern as `workspace-setup-tool`'s
   opened-workspace list).
2. The owning agent (`ConversationalPlanningAgent`) checks
   `consumePendingQuestion()` right after its `LlmClient#complete()` call
   returns and, if present, persists the conversation as
   `AWAITING_HUMAN` and pauses the workflow node (`AgentResult.WaitForEvent`).
3. Once a human replies, the workflow resumes and the conversation
   continues with the answer appended as a new turn.

## Provider/target configuration

Which messaging provider/target `askHuman` posts to is **not hardcoded**
in this tool — it's configured per workflow node (e.g.
`humanInteraction: {provider: slack, target: '#dev-agent-plans'}`) and
resolved via `HumanInteractionClientRegistry`, the same generic port
`SlackGateAgent` could be swapped onto if a future messaging system is
added. The owning agent calls `configureForCurrentCall(provider, target)`
right before every `LlmClient#complete()` call so that thread's `askHuman`
tool call (if any) knows where to post, and `clearCallContext()` in a
`finally` block afterward.

## Used by

- `conversational-planning-agent`

## Collaborators

Implements `PluginContextAware`; `setPluginContext` wires in
`context.humanInteractionClientRegistry()`. An
`AskHumanTool(HumanInteractionClientRegistry)` constructor is also
available for direct construction outside the `ServiceLoader`/
`PluginContext` path.

## Dependencies

```kotlin
dependencies {
    compileOnly(project(":core"))
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
```

Does **not** depend on `tools:tool-support` — `askHuman`'s return value is
a fixed instruction string that can never legitimately be blank, so it has
no need for `ToolResults.orPlaceholder(...)`.

## Notes

- Calling this tool ends the model's turn immediately, by convention
  enforced in the tool's own `@Tool` description — the model is
  instructed not to produce any further tool calls or text after calling
  it.
