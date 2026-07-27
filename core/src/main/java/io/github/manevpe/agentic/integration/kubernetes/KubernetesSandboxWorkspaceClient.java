package io.github.manevpe.agentic.integration.kubernetes;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.manevpe.agentic.config.GitHubProperties;
import io.github.manevpe.agentic.config.SandboxProperties;
import io.github.manevpe.agentic.integration.SandboxWorkspaceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Real {@link SandboxWorkspaceClient}, backed by a single long(er)-lived
 * (but still short-lived and bounded, see below) Kubernetes {@code Pod} per
 * workspace — see ADR-0005 for why this uses a {@code Pod} + {@code exec}
 * rather than the per-task {@code Job} model {@code
 * KubernetesSandboxJobDispatcher} uses for builds/tests.
 *
 * <p>{@link #open} creates a pod running {@code
 * SandboxProperties#workspaceImage()} (see {@code Dockerfile.sandbox-workspace}
 * at the repo root for its default contents — git, coreutils, findutils,
 * grep/sed/awk, and the Java/Node/Go/Python toolchains this project builds
 * and tests against) with a long-running no-op command, waits for it to become
 * ready, then {@code exec}s a {@code git clone} into it. Subsequent
 * list/read/search calls {@code exec} plain shell commands ({@code find},
 * {@code cat}, {@code grep -rn}) against that same pod, addressed by the
 * {@code workspaceId} (the pod's generated name). {@link #close} deletes
 * the pod; {@code spec.activeDeadlineSeconds} is also set as a safety net
 * so an orphaned workspace (a caller that crashes before calling {@link
 * #close}) is force-terminated by Kubernetes rather than leaking forever.
 *
 * <p>When {@code SandboxProperties#dockerInDockerEnabled()} is {@code true},
 * the pod also gets a privileged {@code docker-daemon} sidecar container
 * (a Docker-in-Docker daemon reachable at {@code tcp://localhost:2375} —
 * pod-mates share one network namespace, so {@code localhost} resolves
 * correctly) so repositories whose tests spin up Testcontainers work
 * inside the sandbox. All {@code exec} calls explicitly target the
 * {@code workspace} container by name ({@code inContainer}), since
 * fabric8's exec requires an explicit target once a pod has more than one
 * container.
 * <p>{@code git clone} is authenticated with {@code agentic.github.token}
 * (the same personal access token {@code RestGitHubClient} already uses,
 * embedded in the clone URL as {@code https://<token>@<host>/...}) so
 * private repositories clone correctly — without it, a private repo's
 * clone fails but git still exits, leaving an empty {@code /workspace}
 * that list/read/search tools then silently report as empty rather than
 * erroring, which previously showed up to the LLM as an inexplicably
 * empty checkout. The clone command's exit code is now checked and a
 * failure throws immediately with the captured git output, rather than
 * being swallowed.
 */
@Component
@ConditionalOnProperty(prefix = "agentic.sandbox", name = "enabled", havingValue = "true")
public class KubernetesSandboxWorkspaceClient implements SandboxWorkspaceClient {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxWorkspaceClient.class);
    private static final long EXEC_TIMEOUT_SECONDS = 60;
    private static final String WORKSPACE_CONTAINER_NAME = "workspace";
    private static final String DOCKER_SIDECAR_CONTAINER_NAME = "docker-daemon";
    private static final Path SERVICE_ACCOUNT_TOKEN_PATH =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    private static final Path SERVICE_ACCOUNT_CA_PATH =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");
    // Kubernetes pod names are DNS-1123 subdomains: lowercase alphanumeric,
    // '-' or '.', starting/ending with an alphanumeric character. Every
    // workspaceId we ever hand out comes from open()'s generateName-assigned
    // pod name, so it always matches this. Validating incoming workspaceId
    // arguments against it catches the LLM passing something else instead
    // (most commonly a repository's "owner/repo" string, mistaken for the
    // workspaceId gitClone returned) before it reaches the Kubernetes API —
    // a raw "/" in a pod name gets misparsed by the client into an extra
    // URL path segment, which previously surfaced as a confusing low-level
    // client/RBAC-looking error instead of a clear tool error naming the
    // actual mistake.
    private static final java.util.regex.Pattern VALID_WORKSPACE_ID =
            java.util.regex.Pattern.compile("^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$");

    private final KubernetesClient kubernetesClient;
    private final SandboxProperties properties;
    private final GitHubProperties gitHubProperties;
    private final Map<String, String> workspaceRepositories = new ConcurrentHashMap<>();

    public KubernetesSandboxWorkspaceClient(
            KubernetesClient kubernetesClient, SandboxProperties properties, GitHubProperties gitHubProperties) {
        this.kubernetesClient = kubernetesClient;
        this.properties = properties;
        this.gitHubProperties = gitHubProperties;
    }

    @Override
    public WorkspaceHandle open(String repository, String ref) {
        Pod pod = buildWorkspacePod();

        Pod created = kubernetesClient.pods().inNamespace(properties.namespace()).resource(pod).create();
        String workspaceId = created.getMetadata().getName();

        // Every exit from this point on that doesn't return a handle must
        // delete the pod it just created — otherwise a failure here (e.g.
        // waitUntilReady timing out on a slow/stuck image pull) leaks an
        // orphaned pod on every single retry, since the caller's per-turn
        // workspace cap (see WorkspaceSetupTool#gitClone) only counts
        // workspaces it actually received a handle for. An unbounded
        // number of failed attempts previously caused a runaway pod count
        // even with that cap in place.
        try {
            waitUntilReady(workspaceId);

            String cloneUrl = authenticatedCloneUrl(repository);
            String cloneCommand = ref == null || ref.isBlank()
                    ? "git clone --depth 1 %s /workspace".formatted(cloneUrl)
                    : "git clone --depth 1 --branch %s %s /workspace".formatted(ref, cloneUrl);
            CommandResult cloneResult = execWithExitCode(workspaceId, cloneCommand);
            if (cloneResult.exitCode() != 0) {
                String sanitizedOutput = cloneResult.output().replace(gitHubProperties.token(), "***");
                throw new IllegalStateException(
                        "Failed to clone repository '%s' (ref: %s) into sandbox workspace — git exited with code %d:\n%s"
                                .formatted(repository, ref, cloneResult.exitCode(), sanitizedOutput));
            }

            workspaceRepositories.put(workspaceId, repository);
            log.info("Opened sandbox workspace '{}' for repository '{}' (ref: {})", workspaceId, repository, ref);
            return new WorkspaceHandle(workspaceId, repository);
        } catch (RuntimeException e) {
            kubernetesClient.pods().inNamespace(properties.namespace()).withName(workspaceId).delete();
            // Log the full stack trace (not just the message) here since
            // this is the one place we still have the original,
            // unwrapped exception — LoggingToolCallback only logs the
            // wrapped IllegalStateException's message string, so without
            // this the real root cause (e.g. the underlying fabric8/K8s
            // client failure behind a generic "An error has occurred.")
            // is otherwise never captured anywhere.
            log.warn("Sandbox workspace open failed for repository '{}' (ref: {}, pod: {})", repository, ref, workspaceId, e);
            // Fabric8/Kubernetes exceptions (e.g. a waitUntilReady timeout,
            // or any other client-side failure) often carry an unhelpfully
            // generic message (observed live: literally "An error has
            // occurred."), which is useless both for our own logs and for
            // the LLM deciding what to do next (it can't distinguish "the
            // repository doesn't exist" from "the sandbox infrastructure
            // is unhealthy" from that). Always surface real context —
            // repository/ref/workspaceId and the real exception's own
            // type/message — rather than letting a blank/generic message
            // propagate as-is.
            String causeDetail = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : "%s: %s".formatted(e.getClass().getSimpleName(), e.getMessage());
            throw new IllegalStateException(
                    "Failed to open sandbox workspace for repository '%s' (ref: %s, pod: %s) — %s"
                            .formatted(repository, ref, workspaceId, causeDetail), e);
        }
    }

    /**
     * Split out purely so {@link #open}'s catch block can attribute a
     * failure here to "the pod never became ready" specifically, rather
     * than a generic wrapped message that could just as easily mean the
     * clone step failed.
     */
    private void waitUntilReady(String workspaceId) {
        try {
            kubernetesClient.pods().inNamespace(properties.namespace()).withName(workspaceId)
                    .waitUntilReady(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Sandbox workspace pod '%s' did not become ready within %ds".formatted(workspaceId, EXEC_TIMEOUT_SECONDS),
                    e);
        }
    }

    /**
     * Builds the {@code owner/repo}-relative clone URL with {@code
     * agentic.github.token} embedded as the URL's username (blank
     * password), the same credential shape {@code RestGitHubClient}'s
     * {@code UsernamePasswordCredentialsProvider(token, "")} uses for
     * JGit — GitHub accepts a PAT as either the HTTPS username or
     * password.
     */
    private String authenticatedCloneUrl(String repository) {
        String baseUrl = gitHubProperties.cloneBaseUrl();
        String withoutScheme = baseUrl.replaceFirst("^https://", "");
        return "https://%s@%s/%s.git".formatted(gitHubProperties.token(), withoutScheme, repository);
    }

    /**
     * Builds the workspace pod's spec, kept separate from {@link #open}'s
     * create/wait/clone orchestration purely so it can be unit-tested
     * without needing a mock server to simulate a real kubelet marking the
     * pod Ready (see {@code KubernetesSandboxWorkspaceClientTest}, which
     * mirrors {@code KubernetesSandboxJobDispatcherTest}'s "assert the
     * submitted spec, don't exercise genuine execution" testing depth).
     */
    Pod buildWorkspacePod() {
        var podBuilder = new PodBuilder()
                .withNewMetadata()
                    .withGenerateName("agentic-workspace-")
                    .withNamespace(properties.namespace())
                    .addToLabels("app.kubernetes.io/managed-by", "java-cloud-agent-framework")
                    .addToLabels("agentic.io/purpose", "repo-workspace")
                .endMetadata()
                .withNewSpec()
                    .withActiveDeadlineSeconds((long) properties.workspaceActiveDeadlineSeconds())
                    .withRestartPolicy("Never")
                    .addNewContainer()
                        .withName(WORKSPACE_CONTAINER_NAME)
                        .withImage(properties.workspaceImage())
                        .withCommand("sh", "-c", "sleep " + properties.workspaceActiveDeadlineSeconds())
                        .withEnv(properties.dockerInDockerEnabled()
                                ? List.of(new EnvVar(
                                        "DOCKER_HOST", "tcp://localhost:2375", null))
                                : List.of())
                    .endContainer();

        if (properties.dockerInDockerEnabled()) {
            podBuilder = podBuilder
                    .addNewContainer()
                        .withName(DOCKER_SIDECAR_CONTAINER_NAME)
                        .withImage(properties.dockerInDockerImage())
                        .withArgs("--host=tcp://0.0.0.0:2375", "--host=unix:///var/run/docker.sock")
                        .withEnv(new EnvVar("DOCKER_TLS_CERTDIR", "", null))
                        .withNewSecurityContext()
                            .withPrivileged(true)
                        .endSecurityContext()
                    .endContainer();
        }

        return podBuilder.endSpec().build();
    }

    @Override
    public List<String> listFiles(String workspaceId, String directory, Integer maxDepth) {
        String path = relativePath(directory);
        String depthFlag = maxDepth != null && maxDepth > 0 ? "-maxdepth " + maxDepth + " " : "";
        String output = exec(workspaceId, "find %s %s-type f".formatted(path, depthFlag));
        return output.lines().filter(line -> !line.isBlank()).toList();
    }

    @Override
    public String readFile(String workspaceId, String path) {
        return exec(workspaceId, "cat %s".formatted(relativePath(path)));
    }

    @Override
    public List<String> search(String workspaceId, String pattern) {
        String output = exec(workspaceId, "grep -rn -E %s /workspace".formatted(shellQuote(pattern)));
        return output.lines().filter(line -> !line.isBlank()).toList();
    }

    @Override
    public void writeFile(String workspaceId, String path, String content) {
        String targetPath = relativePath(path);
        String base64Content = java.util.Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String command = "mkdir -p \"$(dirname %s)\" && printf '%%s' %s | base64 -d > %s"
                .formatted(targetPath, shellQuote(base64Content), targetPath);
        exec(workspaceId, command);
    }

    @Override
    public CommandResult runCommand(String workspaceId, String command) {
        return execWithExitCode(workspaceId, "cd /workspace && (%s)".formatted(command));
    }

    @Override
    public String diff(String workspaceId) {
        return exec(workspaceId, "git -C /workspace diff");
    }

    @Override
    public void close(String workspaceId) {
        requireValidWorkspaceId(workspaceId);
        workspaceRepositories.remove(workspaceId);
        kubernetesClient.pods().inNamespace(properties.namespace()).withName(workspaceId).delete();
        log.info("Closed sandbox workspace '{}'", workspaceId);
    }

    private static String relativePath(String path) {
        String trimmed = (path == null || path.isBlank()) ? "." : path;
        return "/workspace/" + trimmed.replaceFirst("^/", "");
    }

    /**
     * Validates that {@code workspaceId} looks like a real pod name (see
     * {@link #VALID_WORKSPACE_ID}) before it reaches any Kubernetes API
     * call. Called at the top of every method that addresses an
     * already-open workspace, to fail fast with a clear tool error
     * instead of a confusing low-level Kubernetes client error.
     */
    private static void requireValidWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank() || !VALID_WORKSPACE_ID.matcher(workspaceId).matches()) {
            throw new IllegalArgumentException(
                    "'%s' is not a valid workspaceId — it must be the exact workspaceId string gitClone "
                            .formatted(workspaceId)
                            + "returned (e.g. 'agentic-workspace-abc12'), not a repository name or anything else.");
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Runs {@code command} inside the workspace pod, wrapping it so its
     * exit code is captured (via a trailing marker line) as well as its
     * combined stdout/stderr, bounded by {@code
     * agentic.sandbox.command-timeout-seconds} — used by {@link
     * #runCommand} for potentially long-running build/test commands.
     */
    private CommandResult execWithExitCode(String workspaceId, String command) {
        String marker = "__AGENTIC_EXIT_CODE__";
        String wrapped = "%s; echo \"%s:$?\"".formatted(command, marker);
        String rawOutput = exec(workspaceId, wrapped, properties.commandTimeoutSeconds());

        int markerIndex = rawOutput.lastIndexOf(marker + ":");
        if (markerIndex < 0) {
            log.warn("Could not find exit-code marker in output for workspace '{}' command: {}", workspaceId, command);
            return new CommandResult(-1, rawOutput);
        }
        String output = rawOutput.substring(0, markerIndex);
        String exitCodeText = rawOutput.substring(markerIndex + marker.length() + 1).strip();
        int exitCode;
        try {
            exitCode = Integer.parseInt(exitCodeText);
        } catch (NumberFormatException e) {
            exitCode = -1;
        }
        return new CommandResult(exitCode, output);
    }

    /**
     * Runs {@code command} inside the workspace pod via {@code kubectl
     * exec}-equivalent streaming and blocks (bounded by {@link
     * #EXEC_TIMEOUT_SECONDS}) until it completes, returning captured
     * stdout.
     */
    private String exec(String workspaceId, String command) {
        return exec(workspaceId, command, EXEC_TIMEOUT_SECONDS);
    }

    /**
     * Runs {@code command} inside the workspace pod by shelling out to the
     * {@code kubectl} binary (see {@code Dockerfile}) rather than using
     * fabric8's own {@code pods().exec(...)}. This was forced by a
     * confirmed fabric8 client bug (reproduced identically on fabric8
     * 6.13.5 and 7.3.1, on both the okhttp and vertx transport modules,
     * and on two different minikube/Kubernetes versions): {@code
     * pods/exec}'s WebSocket upgrade handshake is rejected by the API
     * server with a bare {@code 403 Forbidden} ({@code
     * WebSocketHandshakeException} / "Expected HTTP 101 response but was
     * '403 Forbidden'"), even though the exact same ServiceAccount bearer
     * token used for every other (successful) fabric8 REST call — pod
     * create/get/list/delete — was verified live via a direct {@code
     * kubectl --token=<that token> exec} to work correctly for {@code
     * pods/exec} against the same pod. That direct test conclusively
     * isolated the bug to fabric8's own WebSocket-handshake construction
     * rather than RBAC, the cluster, or the token itself — so exec is
     * delegated to the real, known-working {@code kubectl} binary instead.
     */
    private String exec(String workspaceId, String command, long timeoutSeconds) {
        requireValidWorkspaceId(workspaceId);
        List<String> kubectlCommand = new ArrayList<>(List.of(
                "kubectl",
                "--server=" + kubernetesApiServerUrl(),
                "--certificate-authority=" + SERVICE_ACCOUNT_CA_PATH,
                "--token=" + readServiceAccountToken(),
                "exec",
                "-n", properties.namespace(),
                workspaceId,
                "-c", WORKSPACE_CONTAINER_NAME,
                "--", "sh", "-c", command));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Process process = new ProcessBuilder(kubectlCommand).redirectErrorStream(true).start();
            // Drain stdout/stderr concurrently with waitFor — kubectl's
            // output pipe has a bounded OS buffer, so a command producing
            // more output than that buffer (e.g. `find`/`cat` on a large
            // tree) would otherwise deadlock: kubectl blocks writing once
            // the buffer fills, while we block in waitFor without ever
            // reading it.
            Thread drain = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(out);
                } catch (IOException ignored) {
                    // process destroyed/closed concurrently — nothing further to read
                }
            }, "kubectl-exec-drain-" + workspaceId);
            drain.setDaemon(true);
            drain.start();
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("Timed out waiting for exec command to finish in workspace '{}': {}", workspaceId, command);
                process.destroyForcibly();
            }
            drain.join(TimeUnit.SECONDS.toMillis(5));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to run 'kubectl exec' for sandbox workspace '%s'".formatted(workspaceId), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing sandbox workspace command", e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * The in-cluster API server URL, from the standard {@code
     * KUBERNETES_SERVICE_HOST}/{@code KUBERNETES_SERVICE_PORT} env vars
     * every pod gets injected by the kubelet — used instead of the
     * {@code kubernetes.default.svc} in-cluster DNS name so this also
     * works in clusters/network setups where that DNS name isn't
     * resolvable for some reason.
     */
    private static String kubernetesApiServerUrl() {
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        if (host == null || host.isBlank()) {
            return "https://kubernetes.default.svc";
        }
        return "https://" + host + ":" + (port == null || port.isBlank() ? "443" : port);
    }

    /**
     * Reads the ServiceAccount token fresh on every call (rather than
     * caching it once) so a long-lived pod keeps working correctly across
     * the token's periodic rotation (projected SA tokens are refreshed on
     * disk by the kubelet, typically on the order of once per hour).
     */
    private static String readServiceAccountToken() {
        try {
            return Files.readString(SERVICE_ACCOUNT_TOKEN_PATH, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read in-cluster ServiceAccount token from '%s' for 'kubectl exec'"
                            .formatted(SERVICE_ACCOUNT_TOKEN_PATH), e);
        }
    }
}

