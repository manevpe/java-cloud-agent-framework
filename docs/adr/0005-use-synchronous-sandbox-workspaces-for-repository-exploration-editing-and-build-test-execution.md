# ADR-0005: Use synchronous sandbox workspaces for repository exploration, editing, and build/test execution

**Date**: 2026-07-24
**Status**: accepted
**Deciders**: @manevpe, Copilot CLI

## Context

Planning and coding agents both need to inspect real repositories. Coding agents also need to modify files, run the repository's own commands, and collect a diff. These steps happen inside LLM tool-calling loops, which require several fast round-trips against the same checkout.

A one-shot asynchronous Job model is a poor fit for that interaction pattern because each tool call would need either a brand-new sandbox or a custom callback protocol. A pure GitHub-API approach is also too weak for real local search, edit, and build/test work.

## Decision

We use `SandboxWorkspaceClient` as the single sandbox abstraction for both planning and coding.

The real implementation is `KubernetesSandboxWorkspaceClient`, which:
- creates one Kubernetes Pod per workspace,
- clones the repository into that Pod,
- addresses the workspace by explicit `workspaceId`,
- supports list/read/search for exploration,
- supports write/run/diff for coding,
- shells out to the `kubectl` binary for `exec` calls (not the fabric8 client's own exec, which rejects the WebSocket upgrade with a 403 in-cluster; fabric8 is still used for pod create/get/list/delete/watch),
- deletes the Pod on close, with `activeDeadlineSeconds` as a safety net.

Tool exposure is split by capability:
- `WorkspaceSetupTool`: `gitClone`, list, read, search.
- `FileEditTool`: write, run command, diff.

Workspaces are scoped to a single agent turn and are always closed in `finally` blocks. They are not kept alive across multi-hour human pauses. Read-only discovery tools (`file-read`, `github-api`, `http-request`) are used to narrow down repositories before paying for a clone.

## Alternatives Considered

### Alternative 1: Use one asynchronous Job per implementation run
- **Pros**: Clear isolation boundary and simple fire-and-forget execution model.
- **Cons**: Poor match for synchronous LLM tool-calling loops and repeated repo inspection/edit/build interactions.
- **Why not**: The current agent design needs shared workspace continuity within one turn.

### Alternative 2: Use only remote GitHub/API reads, no live checkout
- **Pros**: Simpler infrastructure and no sandbox lifecycle.
- **Cons**: Cannot support full local search, edit, or build/test execution.
- **Why not**: The coding workflow requires a real working tree.

### Alternative 3: Keep workspaces alive across pauses waiting for humans
- **Pros**: Could avoid recloning after a later resume.
- **Cons**: Long-lived idle pods violate the short-lived sandbox model and leak cost/resources.
- **Why not**: Human pauses can last far longer than a sandbox workspace should.

### Alternative 4: Use fabric8's native `pods/exec` WebSocket client
- **Pros**: One Kubernetes client library for every pod operation, no external process dependency.
- **Cons**: fabric8's exec handshake is rejected with a bare 403 by the cluster's API server regardless of client version/transport, while an equivalent direct `kubectl exec` with the same token succeeds.
- **Why not**: The bug is isolated to fabric8's client-side WebSocket handshake construction; shelling out to the real `kubectl` binary sidesteps it entirely.

## Consequences

### Positive
- One sandbox model for planning and coding.
- Real repository search, editing, and build/test execution happen in isolation from the orchestrator pod.
- Explicit `workspaceId` addressing supports multiple repositories in one agent turn.

### Negative
- No cross-turn workspace reuse or caching.
- A process restart during an in-flight tool-calling turn still loses that turn's transient progress.
- Kubernetes sandbox behavior depends on operational availability of the workspace image, the `kubectl` binary in the runtime image, and cluster access.

### Risks
- Repeated uncertain cloning could create too many workspaces.
  - **Mitigation**: deduplicate clones per turn and cap distinct workspace attempts.
- Some repositories need Docker-in-Docker for Testcontainers.
  - **Mitigation**: support an optional sidecar/DIND mode in the workspace pod.
