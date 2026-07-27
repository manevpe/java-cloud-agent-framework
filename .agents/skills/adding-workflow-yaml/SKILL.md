---
name: adding-workflow-yaml
description: Author a new workflow definition YAML for java-cloud-agent-framework's LangGraph4j engine — node/edge/trigger schema, gate-vs-agent node patterns, interruptsAfter/resume semantics, and knowledgeSources config. Use whenever adding or modifying a workflow graph.
---

# Adding a Workflow YAML

Workflows in this framework are **data, not code**: a YAML file is parsed into a `WorkflowDefinition` and turned into an executable LangGraph4j graph by `WorkflowGraphFactory` — adding a new workflow (or a new step in an existing one) should never require writing new engine Java code, only YAML plus (if needed) a new `EdgeCondition` bean or a new agent module.

Use `workflows/examples/jira-to-pr.yaml` and `jira-to-pr-conversational.yaml` as living templates — copy the closest one and adapt it rather than writing from scratch.

## When to Activate

- User asks to add a new workflow ("create a workflow that...")
- User asks to add a step/node to an existing workflow
- User asks how workflow YAML files are structured, or hits an error loading one
- User asks about pausing/resuming, human-in-the-loop gates, or trigger conditions

## Top-Level Schema

```yaml
workflow:
  id: my-workflow                        # referenced in the webhook URL: POST /webhooks/{id}/start
  trigger:
    type: jira-webhook                    # free-form label describing the source; matched against
    source: jira                          #   inbound payloads by TriggerConditionEvaluator, not an enum
    condition: "labels contains 'ready-for-dev'"   # safety-net filter re-checked even though the caller
                                                     #  already decided to hit /start — most external
                                                     #  webhooks fire broadly, not only on your exact event
  nodes:
    - id: plan
      agent: planning-agent               # matches an Agent implementation's type(), not a class name
      config:                             # opaque, agent-specific — validated by the agent itself, not the engine
        llmProfile: default
        knowledgeSources:
          - type: directory
            path: ./knowledge/backstage-catalog
          - type: neo4j
            database: domain-knowledge
      # requiresApproval: true            # see "Pausing" below
      # resumeTrigger:                    # see "Pausing" below
      #   type: slack-thread-reply
      #   source: slack

    - id: gate
      agent: slack-gate-agent
      ...

  edges:
    - from: plan
      to: gate
      condition: hasOpenQuestions          # named EdgeCondition bean; omit `condition` for an unconditional edge
    - from: plan
      to: post-plan-to-jira
      condition: noOpenQuestions
    - from: post-plan-to-jira
      to: END                              # optional explicit END edge for clarity; falling through with no
                                            #  matching edge also ends the run (see WorkflowRoutingFunction)
```

### Field reference (from `core`'s records — read these Javadocs for authoritative detail)

- **`TriggerDefinition`** (`type`, `source`, `condition`, `event`) — `type`/`source` are free-text labels, not validated against a fixed enum; `condition` is evaluated by `TriggerConditionEvaluator` against the inbound payload on every `/start` call (see `WorkflowWebhookController`); `event` is used for resume-style triggers to disambiguate which kind of inbound event this is (e.g. distinguishing a Slack reply from a Slack reaction).
- **`NodeDefinition`** (`id`, `agent`, `config`, `requiresApproval`, `resumeTrigger`) — `agent` must match a registered `Agent.type()` (built-in or plugin-provided); `config` is a free-form map, entirely owned/interpreted by that agent — the engine never inspects it.
- **`EdgeDefinition`** (`from`, `to`, `condition`) — `condition` is a bean/plugin-condition name resolved by `ConditionRegistry`; omit it (or leave blank) for an always-true edge. **Edges from the same node are evaluated in YAML order; the first match wins** — order matters, put more specific conditions before catch-alls.
- **First node in `nodes:` is the entry point** — there's no separate `start:` field; whichever node is listed first receives the `START -> ...` edge.

## Pausing for Human-in-the-Loop (`requiresApproval` / `resumeTrigger`)

A node that sets `requiresApproval: true` and/or a `resumeTrigger` gets registered with LangGraph4j's native `CompileConfig.interruptsAfter(...)`: the graph engine stops **right after that node runs**, and the checkpoint written becomes the durable "paused, waiting on X" record. There is no polling loop or separate "wait" node type — this is a first-class compile-time property of the node itself.

Resuming happens via `POST /webhooks/resume/{correlationKey}`, which calls `WorkflowEngineService.resumeByCorrelationKey`, merging the inbound event payload into state and continuing the graph from the checkpoint (`CompiledGraph.invoke(GraphInput.resume(payload), config)` under the hood — never a plain `Map` invoke, which would restart from `START` instead of resuming).

**Known LangGraph4j constraint worth knowing before designing a node**: `interruptsAfter` excludes self-loops — a node cannot pause-and-then-repeat itself; if you need a "keep looping until a condition is met, with a pause each iteration" shape (e.g. the PR feedback loop), route through a **separate gate node** that the agent node loops back into, rather than trying to have one node interrupt-after itself. See `pr-comment-gate-agent` + `canContinuePrFeedbackLoop` for the reference pattern.

Also note `WorkflowGraphFactory` compiles with `interruptBeforeEdge(true)`: routing-edge evaluation for a paused node is deferred until resume time, so edge conditions can safely depend on data that only arrives via the resume payload (e.g. an async sandbox job's `testsPassed` callback) — you don't need to work around stale pre-pause state when writing such a condition.

## Gate Node vs. Agent Node

A recurring pattern in the example workflows: a "gate" agent (`slack-gate-agent`, `pr-comment-gate-agent`, `conversation-resume-gate-agent`) exists purely to pause-and-wait-for-an-external-event and normalize its payload into state, separate from the "real" agent that acts on the result. Prefer this split over teaching one agent both "do the work" and "wait for a reply" — it keeps `resumeTrigger`/`requiresApproval` config isolated to a small, single-purpose node and lets the same gate agent be reused across workflows.

## Conditions (`EdgeCondition` beans)

Edge `condition:` names resolve to Spring `EdgeCondition` beans (see `WorkflowConditions`, one `@Bean` method per condition, method name = YAML name) merged with any plugin-provided conditions (`ConditionRegistry` — a plugin class's decapitalized simple name becomes its YAML-referenceable name). If your new workflow needs genuinely new branching logic not already covered by an existing condition (`hasOpenQuestions`, `noOpenQuestions`, `sandboxTestsPassed`, `conversationAwaitingHuman`, `conversationComplete`, `canContinuePrFeedbackLoop`), add a new `@Bean` to `WorkflowConditions` (core, if it's generic) or ship an `EdgeCondition` implementation from your own plugin module — don't encode branching logic inside an agent's Java code where the engine can't see or test it.

## `knowledgeSources` Config

Any node can declare its own `config.knowledgeSources: [...]`, a list of `{type: directory, path: ...}` or `{type: neo4j, database: ...}` specs, resolved per-call by `NodeKnowledgeSourceResolver` — **there is no implicit "query everything" fallback**; a node with no `knowledgeSources` gets no knowledge context at all. Different nodes in the same workflow can read from entirely different sources; sources are cached (by path/database) across the app's lifetime, not per-request.

## Test Workflow Resources

Integration-test-only workflow YAMLs live under `agents-integration-tests/src/test/resources/workflows/{webhook-ingress-test,coding-pr-test,pr-feedback-loop-test,conversational-planning-test}/` — one directory per scenario. If your new workflow shape needs end-to-end coverage, add a new scenario directory here rather than only relying on the two `workflows/examples/*.yaml` files (which are living documentation/starting points for real deployments, not test fixtures).

## Verifying

There's no standalone YAML linter/schema validator — the fastest feedback loop is:

```bash
./gradlew :agents-integration-tests:test --tests "*<RelevantScenario>*"
```

which loads your YAML through the real `WorkflowConfigLoader` -> `WorkflowGraphFactory` path and will surface schema errors (unknown agent type, unresolved condition name, missing required trigger fields) immediately. For a quick structural sanity check without running tests, `WorkflowDefinition`/`NodeDefinition`/`EdgeDefinition` constructors `Objects.requireNonNull` their required fields, so malformed YAML fails fast at load time with a clear message rather than silently producing a broken graph.
