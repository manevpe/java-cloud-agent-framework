# ADR-0004: Auto-execute low-risk side effects and use PR review as the code-change checkpoint

**Date**: 2026-07-24
**Status**: accepted
**Deciders**: @manevpe, Copilot CLI

## Context

The built-in Jira-to-PR workflows perform external side effects:
- posting the drafted plan back to Jira,
- opening a pull request,
- replying to pull-request review feedback,
- pushing amending commits onto the PR branch.

Adding a separate approval gate before each of those actions would make the workflow much heavier and would duplicate the human review already built into the pull-request lifecycle.

At the same time, the engine still has a general `requiresApproval` / `WaitForApproval` capability that custom workflows may use later.

## Decision

Built-in v1 workflows auto-execute low-risk or easily reversible side effects.

Specifically:
- `JiraUpdaterAgent` posts the plan comment immediately.
- `CodingAgent` opens the PR immediately once tests pass.
- `CodingAgent` posts PR-review replies immediately.
- If review feedback needs code changes, `CodingAgent` pushes the amending commit directly to the PR branch.

The human checkpoint for code changes is the pull-request review and merge process itself, not a separate pre-PR or per-amendment approval step.

The engine retains approval-gate support as an extension point, but the built-in workflows use event-driven pause nodes rather than approval-gated nodes. No built-in workflow currently uses `requiresApproval`, and there is no dedicated approval-decision ingress endpoint yet — `requiresApproval` remains available for custom workflows to adopt ahead of that.

## Alternatives Considered

### Alternative 1: Require approval before opening the PR
- **Pros**: Adds one more safety checkpoint before any GitHub-visible change.
- **Cons**: Duplicates the PR review checkpoint and complicates the workflow without materially changing risk.
- **Why not**: The PR exists specifically to be the human review boundary.

### Alternative 2: Require a separate approval before each amendment commit
- **Pros**: Maximum human control over every generated code push.
- **Cons**: Makes review-response cycles much slower and noisier.
- **Why not**: Review comments already feed another PR-review cycle after the amendment.

## Consequences

### Positive
- Built-in workflows remain linear and practical.
- Human review happens at the artifact developers already use: the PR.
- Review-response loops stay fast enough to be useful.

### Negative
- The built-in v1 flow does not exercise a full approval-gated execution UX.
- Operators wanting pre-side-effect approvals must provide that workflow behavior explicitly.

### Risks
- Teams may assume every external side effect is approval-gated because the engine supports `WaitForApproval`.
  - **Mitigation**: document clearly that built-in v1 flows rely on PR review as the human checkpoint.
- Custom workflows may adopt `requiresApproval` before a dedicated approval ingress/UI is defined.
  - **Mitigation**: treat approval gating as an advanced extension point until that operator-facing path is finalized.
