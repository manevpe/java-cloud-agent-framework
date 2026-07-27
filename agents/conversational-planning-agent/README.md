# conversational-planning-agent

`Agent#type()`: **`conversational-planning-agent`**

Drafts an implementation plan the same way
[`planning-agent`](../planning-agent/README.md) does, but as a persistent
`ConversationSession` the LLM can pause and resume as many times as it
needs via the `askHuman` tool — the "cloud Copilot CLI"-style alternative
described in ADR-0003, rather than the fixed single `human-gate` round.

## Workflow role

```yaml
- id: plan
  agent: conversational-planning-agent
  config:
    llmProfile: vertex-gemini-planner
    knowledgeSources:
      - type: directory
        path: ./knowledge/backstage-catalog
      - type: neo4j
        database: domain-knowledge
    humanInteraction:
      provider: slack
      target: "#dev-agent-plans"
    maxConversationRounds: 12   # default 12 if omitted
```

See `workflows/examples/jira-to-pr-conversational.yaml` for the full
workflow — a drop-in replacement for `jira-to-pr.yaml`'s
`plan`/`await-clarifications` pair, with every downstream node identical.

Per the LangGraph4j constraint that a node can't both be an
`interruptsAfter` point and conditionally continue (see
`WorkflowGraphFactory`'s Javadoc), this agent **never pauses itself**: it
always returns `AgentResult.Continue`, setting `conversationStatus`/
`humanQuestionCorrelationKey` in state when it wants to pause. The actual
pause point is the separate
[`conversation-resume-gate-agent`](../conversation-resume-gate-agent/README.md)
node the routing sends control to next (`conversationAwaitingHuman`
condition), which loops back into this node once a reply arrives.

## Behavior

1. Loads or starts a `ConversationSession` (keyed by
   `conversationSessionId` in state, if this is a resumed round).
2. If a `humanReply` is present and the session is `AWAITING_HUMAN`,
   appends it as a turn and marks the session resumed.
3. Builds a prompt from the full conversation transcript so far, calls
   `LlmClient#complete`, offering `file-read`, `workspace-setup`, and
   `ask-human` tools. Calls `AskHumanTool#configureForCurrentCall(provider,
   target)` first, from this node's own `humanInteraction` config, so any
   `askHuman` call knows where to post.
4. If the model called `askHuman` (checked via
   `AskHumanTool#consumePendingQuestion()`) and rounds aren't exhausted,
   persists the session as `AWAITING_HUMAN` and returns `Continue` with
   `conversationStatus=AWAITING_HUMAN` + the correlation key — routing
   then sends control to `conversation-resume-gate`.
5. Otherwise, the LLM's response text is the final plan; marks the
   session `COMPLETED` and sets `plan`/`finalPlan` in state.

### No-LLM fallback

Mirrors `planning-agent`'s no-LLM-configured fallback (no `askHuman` loop
is possible without a model) — produces the same structured plan text
from ticket/summary/description/knowledge with no clarification.

### Rounds-exhausted handling

Once `maxConversationRounds` human replies have been used, the prompt
tells the model not to call `askHuman` again and to produce a final plan
noting any remaining uncertainty. If the model tries anyway, the agent
forces a plan noting the unresolved question instead of looping forever.

## State read/written

| Key | Read | Written |
|---|---|---|
| `ticketKey`, `summary`, `description` | ✓ | |
| `WorkflowState.KNOWLEDGE_CONTEXT` | ✓ | |
| `conversationSessionId` | ✓ | ✓ |
| `humanReply` | ✓ | |
| `conversationStatus` | | ✓ (`AWAITING_HUMAN` or `COMPLETED`) |
| `humanQuestionCorrelationKey` | | ✓ |
| `plan`, `finalPlan`, `hasOpenQuestions` (always `false` on completion) | | ✓ |

## Configuration (`node.config`)

| Key | Default | Meaning |
|---|---|---|
| `maxConversationRounds` | `12` | Max `askHuman` round-trips before the model must finalize |
| `humanInteraction.provider` | `slack` | Messaging provider `askHuman` posts through |
| `humanInteraction.target` | `#dev-agent-plans` | Channel/target `askHuman` posts to |
| `knowledgeSources` | *(none)* | Resolved by the engine, not this agent |

Prompt template locations are overridable via
`agentic.llm.conversational-planning.system-prompt-location` /
`.user-prompt-location` (default
`classpath:prompts/conversational-planning-system-prompt.st` /
`conversational-planning-user-prompt.st`).

## Required tools

`file-read`, `workspace-setup`, `ask-human`.

## Collaborators

Implements `PluginContextAware`. `setPluginContext` wires in
`context.llmClient()`, `context.conversationSessionRepository()`, and
resolves the concrete `AskHumanTool`/`WorkspaceSetupTool` instances (for
`configureForCurrentCall`/`clearCallContext` and
`closeAllOpenedInCurrentCall` respectively — same per-turn workspace
lifecycle as `planning-agent`, see ADR-0005).

## Dependencies

```kotlin
dependencies {
    compileOnly(project(":core"))
    compileOnly("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-core")
    compileOnly("org.springframework.ai:spring-ai-model")
    implementation(project(":tools:file-read-tool"))
    implementation(project(":tools:workspace-setup-tool"))
    implementation(project(":tools:ask-human-tool"))

    testImplementation(project(":core"))
}
```
