# Agents

Each directory under `agents/` is a standalone Gradle module publishing
one `Agent` — the framework's unit of workflow-node behavior (see
`io.github.manevpe.agentic.agent.Agent` and ADR-0006, ADR-0009). A
workflow YAML node names an agent by its `Agent#type()` under `node.agent`;
`AgentRegistry` merges built-in (Spring-bean) agents with any
plugin-provided ones and fails startup on a `type()` collision.

This is exactly the same plugin contract a third-party agent uses — see
`docs/plugins.md` and `plugins-template/agent-template/` for the generic
mechanism and a copy-and-adapt starting point.

## The `jira-to-pr` workflow

These 7 agents implement this repository's reference workflow — see
`workflows/examples/jira-to-pr.yaml` (fixed single-round Slack
clarification) and `workflows/examples/jira-to-pr-conversational.yaml`
(multi-round conversation-session clarification, ADR-0003) for the two
complete, runnable variants:

```
 [webhook: Jira label] 
        │
        ▼
  ┌───────────┐  hasOpenQuestions   ┌─────────────────────┐
  │   plan    │────────────────────▶│ await-clarifications │
  │(planning- │◀────────────────────│    (human-gate)      │
  │  agent)   │   (loops back)      └─────────────────────┘
  └───────────┘
        │ noOpenQuestions
        ▼
  ┌──────────────┐
  │  post-plan   │  (jira-updater-agent)
  └──────────────┘
        │
        ▼
  ┌──────────────┐
  │  implement   │  (coding-agent, mode: implement)
  └──────────────┘
        │ sandboxTestsPassed
        ▼
  ┌────────────────────┐  canContinuePrFeedbackLoop  ┌─────────────────────────┐
  │  pr-feedback-loop   │◀───────────────────────────│ respond-to-pr-comment   │
  │ (pr-comment-gate)   │────────────────────────────▶│  (coding-agent, mode:   │
  └────────────────────┘                              │  respond-to-review)    │
                                                        └─────────────────────────┘
```

The conversational variant swaps `plan`/`await-clarifications` for
`conversational-planning-agent`/`conversation-resume-gate` (see
ADR-0003), keeping every downstream node identical.

## Module index

| Module | `Agent#type()` | Role | Pauses the workflow? |
|---|---|---|---|
| [`planning-agent`](planning-agent/README.md) | `planning-agent` | Drafts an implementation plan from ticket + domain knowledge; flags open questions | No (routes to a gate node instead) |
| [`slack-gate-agent`](slack-gate-agent/README.md) | `human-gate` | Posts a Slack thread and waits for a reply | Yes — `AgentResult.WaitForEvent` |
| [`conversational-planning-agent`](conversational-planning-agent/README.md) | `conversational-planning-agent` | Same as `planning-agent`, but drives its own multi-round `askHuman` conversation | No (routes to a gate node instead) |
| [`conversation-resume-gate-agent`](conversation-resume-gate-agent/README.md) | `conversation-resume-gate` | Generic pause point for any conversation-session-based agent | Yes — `AgentResult.WaitForEvent` |
| [`jira-updater-agent`](jira-updater-agent/README.md) | `jira-updater-agent` | Posts the finalized plan back to the Jira ticket | No |
| [`coding-agent`](coding-agent/README.md) | `coding-agent` | Implements the plan and opens a PR (`mode: implement`); reacts to PR review feedback (`mode: respond-to-review`) | No |
| [`pr-comment-gate-agent`](pr-comment-gate-agent/README.md) | `pr-comment-gate` | Waits for the next PR review/merge/close event | Yes — `AgentResult.WaitForEvent` |

## Conventions shared across every module

- **One agent type per module** — the module directory name matches (or
  closely mirrors) the `Agent#type()` id workflow YAML uses to reference
  it (e.g. `coding-agent` module → `type() == "coding-agent"`; the
  exception is `slack-gate-agent`, whose type is the more
  YAML-descriptive `human-gate`).
- **`PluginContextAware` for real collaborators.** Every agent that needs
  a framework collaborator (an `LlmClient`, `JiraClient`, `GitHubClient`,
  `SlackClient`, a `ConversationSessionRepository`, ...) implements
  `io.github.manevpe.agentic.plugin.PluginContextAware` and receives it
  via `setPluginContext(PluginContext)` — the same mechanism a
  `ServiceLoader`-discovered third-party plugin agent uses, so built-in
  and plugin agents are wired identically.
- **`compileOnly(project(":core"))`.** Every module compiles against
  `:core`'s stable plugin API surface only (see `docs/plugins.md`'s
  "Plugin API surface" list), never bundling `:core` itself — the
  framework's own classloader provides it at runtime.
- **One LLM client, two behaviors, no strategy layer.** Every LLM-backed
  agent here always builds the same prompt and offers the same tools
  regardless of whether an LLM provider is actually configured — see
  `io.github.manevpe.agentic.integration.LlmClient`'s Javadoc.
  `agentic.llm.enabled=false` swaps in a `LoggingLlmClient` that always
  returns a blank completion; every agent detects that blank response and
  falls back to a deterministic heuristic rather than needing a separate
  code path or bean.
- **Node config, not hardcoded values.** Anything that varies per
  workflow node — Slack channel, `maxClarificationRounds`/
  `maxConversationRounds`/`maxIterations`, `knowledgeSources`, which
  messaging provider `askHuman` posts to — is read from
  `NodeDefinition#config()` at execution time, never hardcoded in the
  agent or read from a global Spring `@ConfigurationProperties` bean. This
  is what lets the same agent module serve multiple differently
  configured nodes (see `coding-agent`'s two YAML nodes with different
  `mode` values).
- **Knowledge context is engine-resolved, not agent-resolved.** A node's
  own `knowledgeSources: [...]` YAML config is resolved generically by
  the workflow engine itself (`WorkflowNodeAction`) *before* the agent
  ever runs, and handed to it as a plain `List<String>` under
  `WorkflowState.KNOWLEDGE_CONTEXT`. Agents never know or care whether a
  source was a local directory or a Neo4j query.
