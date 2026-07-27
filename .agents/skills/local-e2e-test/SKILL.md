---
name: local-e2e-test
description: Run java-cloud-agent-framework's full jira-to-pr workflow locally against real Jira, Slack, GitHub, and a local Kubernetes (minikube) sandbox. Automates the docs/local-testing.md walkthrough and captures common gotchas (kubectl exec, pod naming, credential setup, JGit patch-apply limits).
---

# Running the Local End-to-End Test

This automates/guides the walkthrough in `docs/local-testing.md` — running the whole `jira-to-pr` (or `jira-to-pr-conversational`) workflow on a developer machine against **real** Jira/Slack/GitHub accounts and a **real** local Kubernetes cluster for the coding agent's sandbox, instead of the logging-stub/no-sandbox mode used by default. Treat `docs/local-testing.md` as the source of truth if this skill and that doc ever disagree — update the doc first, then this skill.

## When to Activate

- User asks to "run the local test", "test end to end", "try the whole workflow locally"
- User is debugging why a real Jira/Slack/GitHub/sandbox integration isn't behaving as expected
- User asks about the local `minikube` sandbox setup for `CodingAgent`

## Prerequisites Checklist

Before starting, confirm the user has (ask if unclear rather than assuming):
- Docker engine running (Docker Desktop or equivalent)
- `minikube` and `kubectl` installed
- JDK 25 (must match `java.toolchain.languageVersion` in the root `build.gradle.kts`)
- Jira Cloud site + an Atlassian API token
- Slack app installed with `chat:write`, bot token (`xoxb-...`)
- GitHub PAT (classic `repo` scope, or fine-grained Contents+PRs read-write) against a **real test repo the user owns** — this will push branches and open real PRs
- Optionally, an LLM provider configured (`agentic.llm.enabled=true` plus either Vertex AI Gemini credentials via `gcloud auth application-default login`, or a GitHub Models PAT with `models: read`) — without this, `PlanningAgent`/`CodingAgent` can't run at all, since they need an `LlmClient` bean

## Step-by-Step

### 1. Backing stores

```bash
docker compose up -d   # Postgres on :5432, Neo4j on :7687 — see compose.yaml
```

### 2. Local Kubernetes cluster + sandbox image

Use an isolated minikube profile so this never touches other kubeconfig contexts:

```bash
minikube start -p agentic-local --driver=docker
kubectl --context agentic-local get nodes   # sanity check

docker build -f Dockerfile.sandbox-workspace -t agentic-sandbox-workspace:local .
docker save agentic-sandbox-workspace:local -o /tmp/agentic-sandbox.tar
minikube image load /tmp/agentic-sandbox.tar -p agentic-local
rm /tmp/agentic-sandbox.tar
minikube image ls -p agentic-local | grep sandbox   # must show the image before proceeding
```

**Gotcha**: `minikube image load` needs a tarball (`docker save`), not a direct image reference, when the image was built via buildx/containerd rather than the classic Docker image store — skipping the save/load-tarball step is a common cause of `ErrImageNeverPull`/`ImagePullBackOff` on the sandbox pod.

Running the app on the host (not inside the cluster) means it talks to the cluster through the current `~/.kube/config` context. `minikube start` already points that context at the new cluster with cluster-admin rights, so no extra RBAC/ServiceAccount setup is needed for this local flow (that only matters once the app itself runs *inside* the cluster — see `deploy/helm/.../templates/rbac.yaml`).

### 3. Export real credentials

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/java_cloud_agent_framework
export SPRING_DATASOURCE_USERNAME=agentic
export SPRING_DATASOURCE_PASSWORD=agentic
export JIRA_EMAIL=you@example.com
export JIRA_API_TOKEN=...
export SLACK_BOT_TOKEN=xoxb-...
export GITHUB_TOKEN=ghp_...
```

Never print these back to the user or write them into a committed file.

### 4. Run with real integrations + real sandbox enabled

```bash
./gradlew bootRun --args='
  --agentic.jira.enabled=true
  --agentic.jira.base-url=https://your-domain.atlassian.net
  --agentic.jira.email='"$JIRA_EMAIL"'
  --agentic.jira.api-token='"$JIRA_API_TOKEN"'
  --agentic.slack.enabled=true
  --agentic.slack.bot-token='"$SLACK_BOT_TOKEN"'
  --agentic.github.enabled=true
  --agentic.github.token='"$GITHUB_TOKEN"'
  --agentic.sandbox.enabled=true
  --agentic.sandbox.workspace-image=agentic-sandbox-workspace:local
  --agentic.llm.enabled=true
  --agentic.knowledge.neo4j.enabled=true
'
```

Directory-based `knowledgeSources` (e.g. `backstage-catalog`, `context-files`) don't need extra `--args` — each node's own `config.knowledgeSources` in the workflow YAML already points at a directory path; just make sure that path has real content (see `knowledge/` for the starter example).

**Verify real clients are wired**: watch startup logs for `RestJiraClient`/`RestSlackClient`/`RestGitHubClient` (not the `Logging*Client` stub beans — no `[stub]`-prefixed log lines) and `KubernetesSandboxWorkspaceClient` (not `LoggingSandboxWorkspaceClient`). If a `Logging*` bean is still active, the corresponding `agentic.*.enabled` flag didn't take effect — double check the `--args` flag name/value.

### 5. Point the example workflow at the user's real test targets

In `workflows/examples/jira-to-pr.yaml` (or `-conversational`), update:
- The Slack channel referenced by the gate agent's config to a real channel the bot is invited to
- `repository: acme/example-service` under the `implement` node to the user's real `owner/repo`

### 6. Trigger and follow the run

```bash
curl -X POST http://localhost:8080/webhooks/jira-to-pr/start \
  -H 'Content-Type: application/json' \
  -d '{
    "ticketKey": "TEST-1",
    "labels": ["ready-for-dev"],
    "summary": "Add a health-check endpoint",
    "description": "Add GET /ping returning 200 OK with a plain-text body."
  }'
```

Follow along:
- **Open questions?** A new Slack thread appears — after the user replies, resume with:
  ```bash
  curl -X POST http://localhost:8080/webhooks/resume/<slack-thread-ts> \
    -H 'Content-Type: application/json' -d '{"reply": "...their answer..."}'
  ```
- **Plan finalized** → a real comment appears on the Jira ticket.
- **Coding in progress** → `kubectl get pods -n default --context agentic-local --watch` should show a sandbox workspace pod appear while `CodingAgent` clones/edits/builds/tests the repo.
- **Done** → a new branch + real PR appears on the test repo on GitHub.

### 7. Tear down

```bash
docker compose down
minikube delete -p agentic-local
```

## Troubleshooting Notes (learned from prior debugging sessions)

- **`kubectl exec` / fabric8 WebSocket issues**: `KubernetesSandboxWorkspaceClient#exec` deliberately shells out to the `kubectl` binary rather than using fabric8 Kubernetes client's own exec, due to a confirmed WebSocket-handshake bug in fabric8's exec implementation (see that class's Javadoc, and why the production `Dockerfile` installs `kubectl` in the runtime image). If `kubectl` isn't on `PATH` when running `bootRun` locally, sandbox exec calls will fail — make sure it's installed and discoverable from the same shell/environment Gradle runs in.
- **Runaway/stuck sandbox pods**: if a run is interrupted (app killed mid-execution, sandbox timeout not reached), the sandbox pod may be left running. Check `kubectl get pods -n default --context agentic-local` for leftover pods after a failed/aborted run and clean up manually (`kubectl delete pod ... --context agentic-local`) before retrying, especially on resource-constrained local VMs (e.g. Colima) — accumulating pods can exhaust local cluster resources over repeated test runs.
- **JGit `ApplyCommand` gaps**: `RestGitHubClient` applies the sandbox's unified diff via JGit, which handles ordinary text edits well but has known edge cases (binary files, some rename/permission-change patches) that plain `git apply` covers and JGit's port doesn't. A patch-apply error on an otherwise-correct PR attempt is a known limitation, not necessarily a bug in the agent's generated diff — cross-check with a manual `git apply` of the same diff before assuming an agent regression.
- **A stuck/hanging planning step**: if `PlanningAgent` never posts to Slack/Jira and the app seems idle, check `agentic.llm.enabled` is actually `true` and the configured provider's credentials are valid — a silently-failing/misconfigured `LlmClient` is the most common cause, not a workflow-graph bug.
- **`minikube start` current-context switch**: always sanity-check `kubectl config current-context` before/after `minikube start -p agentic-local` if the user has other clusters configured — it's easy to accidentally run subsequent `kubectl`/app commands against the wrong cluster.
