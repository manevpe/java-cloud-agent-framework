package io.github.manevpe.agentic.integration.logging;

import io.github.manevpe.agentic.integration.GitHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Placeholder {@link GitHubClient} that logs instead of calling the real
 * GitHub API. Active by default ({@code agentic.github.enabled} unset or
 * {@code false}) — safe for local dev/tests without real GitHub
 * credentials. Swap by enabling {@code agentic.github.enabled=true} (plus
 * {@code agentic.github.token}), which activates {@code RestGitHubClient}
 * instead.
 */
@Component
@ConditionalOnProperty(prefix = "agentic.github", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LoggingGitHubClient implements GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingGitHubClient.class);

    @Override
    public String pushBranchAndOpenPullRequest(
            String repository, String branchName, String commitMessage,
            String diff, String prTitle, String prDescription) {
        log.info(
                "[stub] Would push branch '{}' to '{}' (commit: '{}') and open PR '{}':\n{}\n\nDiff:\n{}",
                branchName, repository, commitMessage, prTitle, prDescription, diff);
        return "https://github.com/%s/pull/stub".formatted(repository);
    }

    @Override
    public void postPullRequestComment(String repository, String prUrl, String comment) {
        log.info("[stub] Would post comment on PR '{}' in '{}':\n{}", prUrl, repository, comment);
    }

    @Override
    public String pushAmendingCommit(String repository, String branchName, String commitMessage, String diff) {
        log.info(
                "[stub] Would push amending commit '{}' to branch '{}' in '{}':\n{}",
                commitMessage, branchName, repository, diff);
        return "https://github.com/%s/pull/stub".formatted(repository);
    }

    @Override
    public String readFile(String repository, String path, String ref) {
        log.info("[stub] Would read file '{}' from '{}' (ref: {})", path, repository, ref);
        return "[stub] No real GitHub client configured — cannot read '%s' from '%s'.".formatted(path, repository);
    }

    @Override
    public List<RepositorySummary> listOrganizationRepositories(String organization) {
        log.info("[stub] Would list repositories in organization '{}'", organization);
        return List.of();
    }

    @Override
    public List<CodeSearchResult> searchCode(String query) {
        log.info("[stub] Would search code for query '{}'", query);
        return List.of();
    }
}
