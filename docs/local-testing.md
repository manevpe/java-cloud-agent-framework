# Local end-to-end test: real Jira, Slack, GitHub, and a local Kubernetes sandbox

This walks through running the whole `jira-to-pr` (or `jira-to-pr-conversational`)
workflow on your own machine against **real** Jira, Slack, and GitHub
accounts, plus a **real** local Kubernetes cluster for the coding agent's
sandbox — as opposed to the logging-stub/no-sandbox mode used by default.

## 1. Prerequisites

- Docker Desktop (or another local Docker engine)
- `minikube` (used below — `kind` works equally well if you have it instead)
- `kubectl`
- JDK 25 (matches `java.toolchain.languageVersion` in `build.gradle.kts`)
- A Jira Cloud site + an [Atlassian API token](https://id.atlassian.com/manage-profile/security/api-tokens)
- A Slack workspace where you can install an app with the `chat:write`
  scope, and its bot token (`xoxb-...`)
- A GitHub personal access token (classic, `repo` scope, or fine-grained
  with Contents + Pull requests read-write) against a **real test repo you
  own** — this will actually push branches and open PRs against it
- (Optional but recommended for a genuinely real run) An LLM provider so
  `PlanningAgent`/`CodingAgent` use a real model instead of failing
  (`agentic.llm.enabled`, `false`/off by default — if you leave it off,
  planning/coding agents can't run at all, since they depend on an
  `LlmClient` bean). Two providers are supported out of the box:
  - **Vertex AI Gemini** (`agentic.llm.provider=google-genai`, the
    default) — a GCP project + Application Default Credentials
    (`gcloud auth application-default login`)
  - **GitHub Models** (`agentic.llm.provider=github-models`) — GitHub's
    officially supported, OpenAI-API-compatible inference endpoint
    (models.github.ai), authenticated with a GitHub PAT that has the
    `models: read` permission. This is **not** the same thing as GitHub
    Copilot's own internal completions endpoint, which isn't an
    officially supported third-party/backend API — don't route through
    unofficial Copilot proxies here.

## 2. Start Postgres + Neo4j

```bash
docker compose up -d
```

This starts the two backing stores `application.yml` expects at
`localhost:5432` / `localhost:7687` (see `compose.yaml`).

## 3. Create a local Kubernetes cluster and load the sandbox image

Using an isolated minikube profile keeps this fully separate from any
other kubeconfig contexts you may have (e.g. real cloud clusters) — it's
worth double-checking `kubectl config current-context` before and after,
since `minikube start` switches your current context to the new profile.

```bash
minikube start -p agentic-local --driver=docker
kubectl --context agentic-local get nodes   # sanity check

docker build -f Dockerfile.sandbox-workspace -t agentic-sandbox-workspace:local .
# minikube's `image load` needs a tarball when the image was built via
# buildx/containerd rather than the classic docker image store:
docker save agentic-sandbox-workspace:local -o /tmp/agentic-sandbox.tar
minikube image load /tmp/agentic-sandbox.tar -p agentic-local
rm /tmp/agentic-sandbox.tar
minikube image ls -p agentic-local | grep sandbox   # confirm it's loaded
```

Running the app directly on your host (not inside the cluster, see step
5) means it talks to the cluster via your current `~/.kube/config`
context — `minikube start` already points that context at the new
cluster with cluster-admin rights, so no extra RBAC is needed for this
local test (RBAC in `deploy/helm/.../templates/rbac.yaml` is only needed
once the app itself runs *inside* the cluster with a scoped
ServiceAccount).

## 4. Export real credentials as environment variables

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/java_cloud_agent_framework
export SPRING_DATASOURCE_USERNAME=agentic
export SPRING_DATASOURCE_PASSWORD=agentic

export JIRA_EMAIL=you@example.com
export JIRA_API_TOKEN=...
export SLACK_BOT_TOKEN=xoxb-...
export GITHUB_TOKEN=ghp_...
```

## 5. Run the app with real integrations + real sandbox enabled

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

Directory-based knowledge sources (e.g. `backstage-catalog`,
`context-files` in the example workflows) don't need `--args` overrides —
each node's own `knowledgeSources: [...]` config in the workflow YAML
points directly at a directory path (`{type: directory, path: ...}`), so
just point those paths at real directories of plain-text/Markdown files
(see `knowledge/` in this repo for a starter example). Different nodes in
the same workflow can point at entirely different directories.

Watch the startup logs: `RestJiraClient`/`RestSlackClient`/`RestGitHubClient`
should replace the `Logging*Client` beans (no `[stub]`-prefixed log lines
once real calls start happening), and `KubernetesSandboxWorkspaceClient`
replaces `LoggingSandboxWorkspaceClient`.

## 6. Edit the example workflow for your test repo

Open `workflows/examples/jira-to-pr.yaml` (or the `-conversational`
variant) and change:
- `humanInteraction.target` / the Slack channel referenced by
  `SlackGateAgent`'s config to a real channel your bot is invited to
- `repository: acme/example-service` under the `implement` node to
  `your-github-username/your-test-repo`

## 7. Trigger the workflow

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

- If `PlanningAgent` has open questions, watch your Slack channel for a
  new thread — reply there, then resume the paused thread:
  ```bash
  curl -X POST http://localhost:8080/webhooks/resume/<slack-thread-ts-from-the-reply> \
    -H 'Content-Type: application/json' -d '{"reply": "...your answer..."}'
  ```
- Once the plan is finalized, check the Jira ticket — a real comment with
  the plan should appear.
- Watch `kubectl get pods -n default --context agentic-local
  --watch` — you should see a sandbox workspace pod appear while
  `CodingAgent` clones/edits/builds/tests your test repo.
- Check your test repo on GitHub — a new branch and a real PR should
  appear.

## 8. Tear down

```bash
docker compose down
minikube delete -p agentic-local
```

## Notes / known limitations

- `RestGitHubClient` applies the sandbox's unified diff via JGit's
  `ApplyCommand` — this covers ordinary text file edits well, but has
  known edge cases (binary files, some rename/permission-change patches)
  that a plain `git apply` handles but JGit's port doesn't. If a PR fails
  to open with a patch-apply error on a real repo, that's the likely
  cause.
- The GitHub client uses a personal access token, not a GitHub App — fine
  for this single-user local test; see ADR-0007 and `GitHubProperties`'
  Javadoc for the trade-off if you want to add App-based auth later.
