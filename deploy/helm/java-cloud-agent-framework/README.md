# java-cloud-agent-framework Helm chart

Deploys the java-cloud-agent-framework Spring Boot application to Kubernetes.

## Install

```bash
helm install my-release deploy/helm/java-cloud-agent-framework \
  --set image.tag=1.0.0
```

## Upgrade

```bash
helm upgrade my-release deploy/helm/java-cloud-agent-framework
```

Any change to `values.yaml`'s `config` or `workflows` sections is picked up
automatically: the Deployment's pod template carries a `checksum/config`
annotation (a hash of the rendered ConfigMaps), so `helm upgrade` triggers a
rolling restart whenever either changes — no manual `kubectl rollout
restart` needed.

## Changing workflows (no image rebuild)

Workflow YAML files are **not** baked into the container image. They are
mounted read-only at `/config/workflows` (see `AGENTIC_WORKFLOWS_DIRECTORY`
in the `Dockerfile`) from a ConfigMap rendered from `values.workflows`:

```yaml
workflows:
  jira-to-pr.yaml: |
    name: jira-to-pr
    nodes:
      # ...
  another-workflow.yaml: |
    name: another-workflow
    # ...
```

To add, remove, or edit a workflow: edit this map (directly, or via
`-f values-<env>.yaml` / `--set-file workflows.my-flow\.yaml=path/to/file`),
then `helm upgrade`. The checksum annotation forces the rolling restart that
picks up the change.

## Supplying plugin jars (`agentic.plugins.directory`)

The framework loads `Agent`/`EdgeCondition`/`Skill`/`ToolBundle`
implementations from `*.jar` files in one directory via `ServiceLoader`
(ADR-0007) — every out-of-the-box agent/tool is now its own jar (ADR-0009),
loaded exactly the same way a third-party plugin would be. Which image
variant you deploy determines whether you need this section at all:

- **`ghcr.io/manevpe/java-cloud-agent-framework:X.Y.Z-with-default-modules`** —
  already has every out-of-the-box agent/tool jar baked into
  `/opt/agentic/plugins`. Leave `plugins.enabled: false` (the default);
  nothing further to configure.
- **`ghcr.io/manevpe/java-cloud-agent-framework:X.Y.Z`** (bare) — ships with an
  empty plugins directory. Bring your own agent/tool jars one of two ways:
  1. **Derived image** (simplest): your own `Dockerfile` `FROM` the bare
     image, `COPY`ing your jar(s) into `/opt/agentic/plugins`. No Helm
     changes needed.
  2. **Externally-supplied jars at deploy time**: set `plugins.enabled: true`
     and provide a volume + (typically) an `initContainer` that populates
     it before the app starts, e.g. fetching jars from an OCI artifact
     registry into an `emptyDir`:
     ```yaml
     plugins:
       enabled: true
       volume:
         emptyDir: {}
       initContainers:
         - name: fetch-plugins
           image: my-registry/plugin-jar-fetcher:latest
           command: ["/fetch.sh", "/opt/agentic/plugins"]
           volumeMounts:
             - name: plugins
               mountPath: /opt/agentic/plugins
     ```
     A pre-populated `PersistentVolumeClaim` works the same way (set
     `plugins.volume.persistentVolumeClaim.claimName` and drop
     `initContainers` if nothing needs to populate it at pod startup).

`plugins.mountPath` defaults to `/opt/agentic/plugins`, matching the
Dockerfile's `AGENTIC_PLUGINS_DIRECTORY` default — only change it if you've
also overridden that env var via `values.config`.

## Configuration

Non-secret settings live under `values.config` and are rendered into a
ConfigMap, then wired into every container as environment variables via
`envFrom`. Keys use Spring Boot's relaxed-binding environment variable form
(e.g. `agentic.llm.enabled` -> `AGENTIC_LLM_ENABLED`).

## Secrets

By default (`secrets.create: true`) the chart generates a Secret from the
plaintext values under `values.secrets.values`. This is convenient for a
first deploy or a local/dev cluster, but plaintext values committed to a
values file are **not** appropriate for anything real.

For a real deployment:

1. Set `secrets.create: false`.
2. Either:
   - Pre-create a Secret named `<release-name>-java-cloud-agent-framework` with
     the same keys as `secrets.values` (e.g. via `kubectl create secret`,
     Sealed Secrets, or the External Secrets Operator), or
   - Set `secrets.secretRefName` to point at an existing Secret (of any
     name) that already has those keys.

The chart never requires a specific secret-management tool — it only needs
a Secret object with the right keys to exist by the name it expects (or the
name you point it at).

## Health probes & observability

- Liveness: `GET /actuator/health/liveness`
- Readiness: `GET /actuator/health/readiness`
- Metrics: `GET /actuator/prometheus` (Micrometer + Prometheus registry;
  not exposed via a Service/Ingress path by default — scrape it directly
  or add a `ServiceMonitor`/Ingress rule if you run Prometheus Operator).

Both probes are backed by Spring Boot's own Kubernetes health-probe groups
(`management.endpoint.health.probes.enabled=true`), not custom logic.

## Validating the chart

```bash
helm lint deploy/helm/java-cloud-agent-framework
helm template test-release deploy/helm/java-cloud-agent-framework
```

## Values reference

See `values.yaml` for the full set of configurable values (image, service,
ingress, resources, autoscaling, probes, `config`, `secrets`, `workflows`).

## Sandbox workspace image

When `AGENTIC_SANDBOX_ENABLED=true`, `CodingAgent`/`PlanningAgent` clone
and build/test repositories inside a per-workspace Kubernetes Pod running
`AGENTIC_SANDBOX_WORKSPACE_IMAGE` (see `SandboxWorkspaceClient`,
`SandboxProperties`, ADR-0005, ADR-0008). Build and push that image from
the repo root with:

```bash
docker build -f Dockerfile.sandbox-workspace -t ghcr.io/manevpe/agentic-sandbox-workspace:latest .
docker push ghcr.io/manevpe/agentic-sandbox-workspace:latest
```

It ships git/coreutils/findutils/grep/sed/awk plus Java 25 LTS (Temurin) +
Maven, Node.js 24 LTS + npm, Go, Python 3 + pip, and the Docker CLI — the
toolchains this project expects to build/test against out of the box. For
anything else, build a derived image (`FROM
ghcr.io/manevpe/agentic-sandbox-workspace:latest`) with the extra toolchain
layered on, and set `config.AGENTIC_SANDBOX_WORKSPACE_IMAGE` (or
`--set config.AGENTIC_SANDBOX_WORKSPACE_IMAGE=...`) to point at it.

### Testcontainers support (Docker-in-Docker)

Set `config.AGENTIC_SANDBOX_DOCKER_IN_DOCKER_ENABLED=true` to give every
workspace pod a second, privileged `docker-daemon` sidecar container
(Docker-in-Docker, `config.AGENTIC_SANDBOX_DOCKER_IN_DOCKER_IMAGE` selects
its image) that the workspace container's Docker CLI reaches via
`DOCKER_HOST=tcp://localhost:2375` — pod-mates share one network
namespace, so `localhost` resolves correctly regardless of which
container network plugin/runtime (Docker, containerd, CRI-O) the
cluster's nodes actually use. This is what lets a repository's own test
suite spin up Testcontainers (Postgres, Kafka, etc.) inside the sandbox.

This is **off by default** because the sidecar requires a privileged
security context, which many clusters' Pod Security Standards
("restricted"/"baseline", the default since Kubernetes 1.25) block by
admission control. Before enabling it, label the namespace workspace pods
are created in (`agentic.sandbox.namespace`, defaults to `default`) to
allow privileged pods:

```bash
kubectl label namespace <sandbox-namespace> \
  pod-security.kubernetes.io/enforce=privileged --overwrite
```

Understand the security trade-off before doing this in a shared/production
cluster: a privileged container can access the host's devices and, with
enough effort, escape to the node. Consider a dedicated namespace/cluster
for sandboxes that need this, and keep `AGENTIC_SANDBOX_WORKSPACE_ACTIVE_DEADLINE_SECONDS`
tight so a stray privileged pod doesn't outlive its task by much.

