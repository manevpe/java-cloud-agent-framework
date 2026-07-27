package io.github.manevpe.agentic.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code agentic.sandbox.*} application properties controlling the
 * Kubernetes-Pod-backed code sandbox workspace (see {@code
 * SandboxWorkspaceClient}) the coding agent's tool-calling loop uses to
 * clone, explore, edit, and build/test a repository. There is no longer a
 * separate build/test {@code Job} nor an async callback: the coding
 * agent's LLM drives the whole change (write/run/diff) synchronously
 * against the same workspace pod, in-process, the same way planning's
 * repo-exploration tools already did (see the ADR recorded when the
 * sandbox Job dispatch model was replaced with this tool-calling model).
 *
 * @param enabled                        when {@code false} (the default), a logging stub is used
 *                                        instead of creating real Kubernetes workspace pods —
 *                                        safe for local dev/tests without a cluster available
 * @param namespace                      namespace workspace pods are created in
 * @param workspaceImage                 container image the repo workspace pod runs (see {@code
 *                                        SandboxWorkspaceClient} and {@code Dockerfile.sandbox-workspace}
 *                                        at the repo root); expected to contain {@code git},
 *                                        {@code grep}, and whatever build/test tooling the target
 *                                        repositories need
 * @param workspaceActiveDeadlineSeconds safety-net upper bound (Kubernetes {@code
 *                                        activeDeadlineSeconds}) on how long a workspace pod may run
 *                                        before Kubernetes force-terminates it, in case a caller
 *                                        crashes before explicitly closing the workspace (see
 *                                        ADR-0005). Must comfortably exceed {@code
 *                                        commandTimeoutSeconds} — a single coding session can run
 *                                        several long build/test commands back-to-back against the
 *                                        same workspace, and if this safety net is shorter than a
 *                                        single command's own timeout, Kubernetes can force-kill the
 *                                        pod out from under an in-flight {@code runCommand} exec,
 *                                        which then hangs indefinitely waiting on a connection to a
 *                                        pod that no longer exists instead of failing cleanly
 * @param commandTimeoutSeconds          upper bound on how long a single {@code runCommand} call
 *                                        (e.g. a build/test command) may take inside a workspace pod
 *                                        before it's treated as timed out
 * @param dockerInDockerEnabled          when {@code true}, the workspace pod also gets a
 *                                        privileged Docker-in-Docker sidecar container (see
 *                                        {@code KubernetesSandboxWorkspaceClient#buildWorkspacePod()}),
 *                                        reachable from the workspace container via {@code
 *                                        DOCKER_HOST=tcp://localhost:2375}, so repositories whose
 *                                        tests use Testcontainers can run inside the sandbox.
 *                                        Defaults to {@code false} since the sidecar requires a
 *                                        privileged security context, which many clusters'
 *                                        Pod Security Standards ("restricted"/"baseline") block by
 *                                        default — the target namespace must be labeled/exempted
 *                                        for "privileged" before enabling this (see the Helm
 *                                        chart's README)
 * @param dockerInDockerImage            image the Docker-in-Docker sidecar container runs, only
 *                                        used when {@code dockerInDockerEnabled} is {@code true}
 */
@ConfigurationProperties(prefix = "agentic.sandbox")
public record SandboxProperties(
        boolean enabled,
        String namespace,
        String workspaceImage,
        int workspaceActiveDeadlineSeconds,
        int commandTimeoutSeconds,
        boolean dockerInDockerEnabled,
        String dockerInDockerImage
) {
    public SandboxProperties {
        namespace = (namespace == null || namespace.isBlank()) ? "default" : namespace;
        workspaceImage = (workspaceImage == null || workspaceImage.isBlank())
                ? "ghcr.io/manevpe/agentic-sandbox-workspace:latest" : workspaceImage;
        commandTimeoutSeconds = commandTimeoutSeconds <= 0 ? 1800 : commandTimeoutSeconds;
        // A coding session can run several long build/test commands
        // back-to-back against the same workspace, so the safety-net
        // active deadline must comfortably exceed a single command's own
        // timeout — otherwise Kubernetes can force-kill the pod mid-command,
        // leaving the in-flight exec hanging on a dead connection instead
        // of failing cleanly. Default leaves generous headroom for a
        // multi-command session; if a caller sets both explicitly such
        // that the deadline is still too tight, widen it and warn rather
        // than silently accept a configuration that will hang mid-build.
        int minimumDeadline = commandTimeoutSeconds + 600;
        workspaceActiveDeadlineSeconds = workspaceActiveDeadlineSeconds <= 0
                ? Math.max(7200, minimumDeadline) : workspaceActiveDeadlineSeconds;
        if (workspaceActiveDeadlineSeconds < minimumDeadline) {
            Logger log = LoggerFactory.getLogger(SandboxProperties.class);
            log.warn("agentic.sandbox.workspace-active-deadline-seconds ({}) is too close to or "
                            + "shorter than command-timeout-seconds ({}) — widening it to {}s so a "
                            + "long-running build/test command can't be killed mid-flight by the "
                            + "workspace pod's own safety-net deadline",
                    workspaceActiveDeadlineSeconds, commandTimeoutSeconds, minimumDeadline);
            workspaceActiveDeadlineSeconds = minimumDeadline;
        }
        dockerInDockerImage = (dockerInDockerImage == null || dockerInDockerImage.isBlank())
                ? "docker:27-dind" : dockerInDockerImage;
    }
}
