package io.github.manevpe.agentic.integration.logging;

import io.github.manevpe.agentic.integration.SandboxWorkspaceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Placeholder {@link SandboxWorkspaceClient} that logs instead of creating
 * a real Kubernetes pod. Active by default ({@code agentic.sandbox.enabled}
 * unset or {@code false}) — safe for local dev/tests without a cluster
 * available. Swap by enabling {@code agentic.sandbox.enabled=true}, which
 * activates {@code KubernetesSandboxWorkspaceClient} instead.
 *
 * <p>Mirrors {@link LoggingGitHubClient}'s style: {@link #open} still
 * returns a usable (fake) {@link WorkspaceHandle} so callers don't need to
 * special-case "no sandbox configured", but every read/list/search call
 * returns an explanatory placeholder rather than pretending to have real
 * repository content.
 */
@Component
@ConditionalOnProperty(prefix = "agentic.sandbox", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LoggingSandboxWorkspaceClient implements SandboxWorkspaceClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingSandboxWorkspaceClient.class);

    @Override
    public WorkspaceHandle open(String repository, String ref) {
        String workspaceId = "stub-" + UUID.randomUUID();
        log.info("[stub] Would clone '{}' (ref: {}) into a new sandbox workspace '{}'", repository, ref, workspaceId);
        return new WorkspaceHandle(workspaceId, repository);
    }

    @Override
    public List<String> listFiles(String workspaceId, String directory, Integer maxDepth) {
        log.info("[stub] Would list files under '{}' (maxDepth: {}) in workspace '{}'", directory, maxDepth, workspaceId);
        return List.of("[stub] No real sandbox configured — cannot list files in workspace '%s'.".formatted(workspaceId));
    }

    @Override
    public String readFile(String workspaceId, String path) {
        log.info("[stub] Would read file '{}' from workspace '{}'", path, workspaceId);
        return "[stub] No real sandbox configured — cannot read '%s' from workspace '%s'.".formatted(path, workspaceId);
    }

    @Override
    public List<String> search(String workspaceId, String pattern) {
        log.info("[stub] Would search for '{}' in workspace '{}'", pattern, workspaceId);
        return List.of("[stub] No real sandbox configured — cannot search workspace '%s'.".formatted(workspaceId));
    }

    @Override
    public void writeFile(String workspaceId, String path, String content) {
        log.info("[stub] Would write {} bytes to '{}' in workspace '{}'", content.length(), path, workspaceId);
    }

    @Override
    public CommandResult runCommand(String workspaceId, String command) {
        log.info("[stub] Would run command '{}' in workspace '{}'", command, workspaceId);
        return new CommandResult(0, "[stub] No real sandbox configured — cannot run '%s' in workspace '%s'."
                .formatted(command, workspaceId));
    }

    @Override
    public String diff(String workspaceId) {
        log.info("[stub] Would diff workspace '{}'", workspaceId);
        return "[stub] No real sandbox configured — cannot diff workspace '%s'.".formatted(workspaceId);
    }

    @Override
    public void close(String workspaceId) {
        log.info("[stub] Would close sandbox workspace '{}'", workspaceId);
    }
}
