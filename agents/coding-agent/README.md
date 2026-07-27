# coding-agent

`Agent#type()`: **`coding-agent`**

Owns every coding task in the workflow: turning the finalized plan into a
merged code change and opening a PR for it, and — reused for a second
node — reacting to PR review feedback (deciding whether it requires a
code change, replying, and pushing an amending commit if so).

Registered **once** under `coding-agent` and referenced from two YAML
nodes with different `mode` config values, since `AgentRegistry` resolves
one bean per `type()` but nothing stops multiple node ids from naming the
same type (see ADR-0006).

## Workflow role

```yaml
# Node 1: brand-new implementation
- id: implement
  agent: coding-agent
  config:
    mode: implement
    llmProfile: vertex-gemini-coder
    # repository: paymenttools/reporting-engine   # optional pin — see below
    knowledgeSources:
      - type: directory
        path: ./knowledge/dnd

# Node 2: reacting to PR review feedback
- id: respond-to-pr-comment
  agent: coding-agent
  config:
    mode: respond-to-review
    maxIterations: 5
```

- **`mode: implement`** (the `implement` node): implements
  `finalPlan`/`plan` against the repository's default branch and opens a
  PR via `GitHubClient`.
- **`mode: respond-to-review`** (the `respond-to-pr-comment` node):
  merged/closed events just record and stop; otherwise decides whether
  the review batch needs a code change, always posts a reply (low-risk/
  reversible, auto-executes — see ADR-0004), and — only if a change is
  needed — implements it against the PR's existing branch and pushes an
  amending commit. No separate approval gate on the amendment itself; the
  checkpoint for code changes is the PR review/merge, same as the initial
  change (see ADR-0004).

Both modes **block in-process** for however long the workspace pod's
build/test command takes — safe here because the workflow engine invokes
this node on its background executor, never on the originating webhook's
HTTP request thread (see `WorkflowEngineService`).

## How implementation actually happens

Real code generation, build, and test execution happen out-of-process in
an isolated `SandboxWorkspaceClient` pod (per ADR-0005), driven by this
agent's own LLM tool-calling loop: `gitClone` → explore
(`listWorkspaceFiles`/`readWorkspaceFile`/`searchWorkspace`) → write
(`writeWorkspaceFile`) → run (`runWorkspaceCommand`, checking the exit
code) → `diffWorkspace` for the final diff. The model reports the result
via its own `submitImplementationResult` tool call
(`SubmitImplementationResultTool`), not via its free-text response.

### Repository resolution

The `implement` node deliberately **never hardcodes a target repository**
by default: the model must determine it itself from the node's own
resolved knowledge context (typically an explicit ticket-project-to-
repository mapping, e.g. `knowledge/dnd/repo-mappings.txt`), using the
cheap `github-api`/`file-read` discovery tools before ever cloning. A node
MAY still set `config.repository` to force a specific repository,
skipping inference entirely — useful for a workflow that only ever
targets one repository. A workflow-pinned repository always wins over
whatever the model reports back.

For `respond-to-review`, the repository is already known (recorded in
workflow state from the original implementation), so the model is told it
directly instead.

### No-LLM fallback

A single class handles both the no-LLM-configured and real LLM-backed
cases for both the implementation and the review-response decision. When
no model is configured: implementation reports a failed/untested change
(there is no way to actually implement code without a model), and the
review decision reproduces this project's original keyword-matching
heuristic (`please`/`could you`/`can you`/`fix`/`change`/`update` in a
comment → `needsAmendment`).

## Behavior — `mode: implement`

1. Reads `finalPlan`/`plan`, `ticketKey`, `summary`, this node's own
   resolved knowledge context, and optional `config.repository`.
2. Runs the coding tool-calling loop (`implementCodeChange`) against the
   default branch.
3. If tests passed, opens a PR via
   `GitHubClient#pushBranchAndOpenPullRequest` on a new
   `agentic/<ticketKey-lowercased>` branch, and records `branchName` in
   state for later amending commits.

## Behavior — `mode: respond-to-review`

1. If `prEventType` is `merged`/`closed`, records `prThreadClosed`/
   `prClosedReason` and stops (`needsAmendment=false`).
2. Otherwise, decides whether `reviewComments` need a code change
   (`decidePrResponse`) and always posts a reply via
   `GitHubClient#postPullRequestComment`.
3. If a change is needed, re-runs the coding tool-calling loop against the
   existing `branchName`, and pushes an amending commit via
   `GitHubClient#pushAmendingCommit` if tests pass.

## State read/written

| Key | Read | Written |
|---|---|---|
| `finalPlan`/`plan`, `ticketKey`, `summary` | ✓ | |
| `WorkflowState.KNOWLEDGE_CONTEXT` | ✓ | |
| `diff`, `testsPassed`, `testSummary`, `changedFiles`, `repository` | | ✓ |
| `prUrl`, `branchName` (implement mode, on success) | | ✓ |
| `prEventType`, `reviewComments`, `branchName`, `prUrl`, `repository` (respond-to-review mode) | ✓ | |
| `prFeedbackIteration`, `needsAmendment`, `amendmentPushed` | | ✓ |
| `prThreadClosed`, `prClosedReason` (on merge/close) | | ✓ |
| `prFeedbackMaxIterations` | | ✓ |

## Configuration (`node.config`)

| Key | Default | Applies to | Meaning |
|---|---|---|---|
| `mode` | *(required — no default)* | both | `implement` or `respond-to-review` |
| `repository` | *(none — model infers it)* | `implement` | Pins the target repository, skipping inference |
| `maxIterations` | `5` | `respond-to-review` | Max review/amend cycles via `pr-comment-gate` |
| `knowledgeSources` | *(none)* | `implement` | Resolved by the engine, not this agent |

Prompt template locations are overridable via
`agentic.llm.coding.system-prompt-location`/`.user-prompt-location`
(default `classpath:prompts/coding-system-prompt.st`/
`coding-user-prompt.st`) and
`agentic.llm.pr-response.system-prompt-location`/`.user-prompt-location`
(default `classpath:prompts/pr-response-system-prompt.st`/
`pr-response-user-prompt.st`).

## Required tools

`file-read`, `workspace-setup`, `file-edit`, `github-api`,
`http-request` (union of both modes' tool needs — see
`Agent#requiredTools()`), plus this agent's own private (non-shareable)
`@Tool` methods: `SubmitImplementationResultTool`,
`SubmitPrResponseTool`.

## Collaborators

Implements `PluginContextAware`. `setPluginContext` wires in
`context.gitHubClient()`, `context.llmClient()`, and resolves the
concrete `WorkspaceSetupTool` instance for
`closeAllOpenedInCurrentCall()` (same per-turn workspace lifecycle as
`planning-agent`, see ADR-0005) — the workspace opened by one call is
closed once it returns, never kept alive across the paused workflow's
later, separate PR-review turns.

## Dependencies

```kotlin
dependencies {
    compileOnly(project(":core"))
    compileOnly("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-core")
    implementation(project(":tools:workspace-setup-tool"))
    // For this agent's own private, non-shareable `@Tool` methods
    // (SubmitImplementationResultTool, SubmitPrResponseTool).
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
```

`file-read-tool`, `file-edit-tool`, `github-api-tool`, and
`http-request-tool` are resolved purely by name through
`ToolRegistry#resolveTools(...)` at runtime — no compile-time dependency
on them here, but their jars must still be present in the deployed
plugins directory alongside this one for the agent to actually work (only
`workspace-setup-tool` is a compile-time dependency, since this class
casts to the concrete `WorkspaceSetupTool` type to reuse the same sandbox
pod across calls).
