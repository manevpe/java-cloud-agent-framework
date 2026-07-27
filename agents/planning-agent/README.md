# planning-agent

`Agent#type()`: **`planning-agent`**

Reads a ticket's requirements plus relevant domain knowledge and drafts an
implementation plan, flagging any open questions blocking it. The single
fixed-round-trip half of this framework's two clarification models — see
[`conversational-planning-agent`](../conversational-planning-agent/README.md)
for the multi-round alternative (ADR-0003).

## Workflow role

```yaml
- id: plan
  agent: planning-agent
  config:
    llmProfile: vertex-gemini-planner
    knowledgeSources:
      - type: directory
        path: ./knowledge/example-domain
    maxClarificationRounds: 12   # default 12 if omitted
```

Routes to a `human-gate` node (`hasOpenQuestions` condition) when the
drafted plan has open questions, or straight to `post-plan`
(`noOpenQuestions`) when it doesn't — see
`workflows/examples/jira-to-pr.yaml`. `human-gate` posts to Slack and,
once a developer replies, loops back into this same `plan` node — this
agent redrafts using the *entire* accumulated clarification history each
round (not just the latest raw reply string-concatenated on), up to
`maxClarificationRounds` before proceeding anyway with a note about what's
still unresolved.

## Behavior

1. Reads `ticketKey`, `summary`, `description` from workflow state, and
   this node's own resolved `WorkflowState.KNOWLEDGE_CONTEXT` (populated
   generically by the engine from `knowledgeSources` config before this
   agent runs).
2. If a `slackAnswer` is present in state (i.e. this is a resumed round),
   appends it plus the previous round's open questions onto
   `clarificationHistory`.
3. Builds a prompt (see `prompts/planning-system-prompt.st` /
   `planning-user-prompt.st`) and calls `LlmClient#complete`, offering
   `file-read` and `workspace-setup` tools (read-only `gitClone`/list/
   read/search — see ADR-0005) so the model can inspect existing
   repository code before finalizing the plan.
4. Parses the model's plain-text `STATUS: READY` / `STATUS:
   NEEDS_CLARIFICATION` response (deliberately not JSON — the plan and
   open questions are mutually exclusive, so there's no structured
   multi-field payload to encode).
5. If rounds are exhausted with unresolved questions, proceeds anyway with
   an appended note; otherwise sets `hasOpenQuestions` for the routing
   condition to pick up.

### No-LLM fallback

When no LLM is configured (`agentic.llm.enabled=false`, `LlmClient`
returns a blank completion), falls back to this project's original
deterministic heuristic: a blank description, or one containing `TBD` or
`?`, triggers an open question. Every subsequent round re-checks only the
*most recent* answer (not the original description again), so a concrete
answer resolves the plan but an answer that itself contains `TBD`/`?`
correctly triggers another round.

## State read/written

| Key | Read | Written |
|---|---|---|
| `ticketKey`, `summary`, `description` | ✓ | |
| `WorkflowState.KNOWLEDGE_CONTEXT` | ✓ | |
| `slackAnswer`, `openQuestions` (previous round) | ✓ | |
| `clarificationHistory` | ✓ | ✓ |
| `plan`, `finalPlan` | | ✓ |
| `openQuestions`, `hasOpenQuestions` | | ✓ |

## Configuration (`node.config`)

| Key | Default | Meaning |
|---|---|---|
| `maxClarificationRounds` | `12` | Max Slack round-trips before proceeding regardless |
| `knowledgeSources` | *(none)* | Resolved by the engine, not this agent — see `agents/README.md` |

Prompt template locations are overridable via Spring properties
`agentic.llm.planning.system-prompt-location` /
`agentic.llm.planning.user-prompt-location` (default
`classpath:prompts/planning-system-prompt.st` / `planning-user-prompt.st`).

## Required tools

`file-read`, `workspace-setup` (see `Agent#requiredTools()`) — resolved
via `PluginContext#toolRegistry()`.

## Collaborators

Implements `PluginContextAware`. `setPluginContext` wires in
`context.llmClient()` and resolves the concrete `WorkspaceSetupTool`
instance (via `toolRegistry().resolveInstance(...)`) so it can call
`closeAllOpenedInCurrentCall()` after each LLM turn — workspaces opened
via `gitClone` during plan drafting are scoped to a single planning turn,
never kept alive across the async gap while paused on a Slack reply (see
ADR-0005).

## Dependencies

```kotlin
dependencies {
    compileOnly(project(":core"))
    compileOnly("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-core")
    compileOnly("org.springframework.ai:spring-ai-model")
    implementation(project(":tools:file-read-tool"))
    implementation(project(":tools:workspace-setup-tool"))

    testImplementation(project(":core"))
}
```
