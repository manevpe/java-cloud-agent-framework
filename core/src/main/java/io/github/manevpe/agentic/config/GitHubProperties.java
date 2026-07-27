package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code agentic.github.*} properties for the real, personal-access-token-authenticated
 * {@code GitHubClient} (see {@code integration.github.RestGitHubClient}). Deliberately the
 * simplest viable auth model for a single-user/local-test deployment; a
 * GitHub-App-based (JWT + installation token) adapter can be added later
 * as a sibling implementation behind the same {@code GitHubClient} port
 * without touching any agent code.
 *
 * @param enabled     when {@code false} (the default), {@code LoggingGitHubClient} is used instead
 * @param token       a GitHub personal access token (classic or fine-grained) with
 *                    {@code repo} scope (or Contents/Pull requests read-write for fine-grained)
 * @param apiBaseUrl  GitHub REST API base URL — override for GitHub Enterprise Server
 * @param cloneBaseUrl Git remote base URL used to build the authenticated clone/push URL —
 *                    override for GitHub Enterprise Server
 * @param author      commit author name used for commits this client makes
 * @param authorEmail commit author email used for commits this client makes
 */
@ConfigurationProperties(prefix = "agentic.github")
public record GitHubProperties(
        boolean enabled,
        String token,
        String apiBaseUrl,
        String cloneBaseUrl,
        String author,
        String authorEmail
) {
    public GitHubProperties {
        apiBaseUrl = (apiBaseUrl == null || apiBaseUrl.isBlank()) ? "https://api.github.com" : apiBaseUrl;
        cloneBaseUrl = (cloneBaseUrl == null || cloneBaseUrl.isBlank()) ? "https://github.com" : cloneBaseUrl;
        author = (author == null || author.isBlank()) ? "java-cloud-agent-framework" : author;
        authorEmail = (authorEmail == null || authorEmail.isBlank())
                ? "java-cloud-agent-framework@users.noreply.github.com" : authorEmail;
    }
}
