package io.github.manevpe.agentic.integration.github;

import io.github.manevpe.agentic.config.GitHubProperties;
import io.github.manevpe.agentic.integration.GitHubClient;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Real {@link GitHubClient}, active once {@code agentic.github.enabled=true}
 * (see {@code LoggingGitHubClient}, the default stub). Authenticates with
 * a personal access token ({@code agentic.github.token}, {@code repo}
 * scope) — the simplest viable auth model for a single-user/local-test
 * deployment; a GitHub-App-based adapter can be added later as a sibling
 * implementation behind the same {@link GitHubClient} port.
 *
 * <p>Two concerns are split cleanly: the actual branch/commit/push (JGit,
 * cloning into a throwaway temp directory per call and applying the
 * unified diff the sandbox workspace already produced via {@code git
 * diff}) versus the GitHub-specific PR/comment/file-read operations
 * (plain REST calls against the GitHub REST API v3 via {@link
 * RestClient}).
 */
@Component
@ConditionalOnProperty(prefix = "agentic.github", name = "enabled", havingValue = "true")
public class RestGitHubClient implements GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(RestGitHubClient.class);

    private final GitHubProperties properties;
    private final RestClient restClient;

    public RestGitHubClient(GitHubProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .build();
    }

    @Override
    public String pushBranchAndOpenPullRequest(
            String repository, String branchName, String commitMessage,
            String diff, String prTitle, String prDescription) {
        String defaultBranch = defaultBranch(repository);
        withClone(repository, defaultBranch, true, branchName, git -> {
            applyPatchAndCommit(git, diff, commitMessage);
            pushBranch(git, branchName);
        });

        Map<String, Object> response = restClient.post()
                .uri("/repos/{repository}/pulls", repository)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("title", prTitle, "body", prDescription, "head", branchName, "base", defaultBranch))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        String prUrl = String.valueOf(response.get("html_url"));
        log.info("Opened PR '{}' on '{}' (branch '{}')", prUrl, repository, branchName);
        return prUrl;
    }

    @Override
    public void postPullRequestComment(String repository, String prUrl, String comment) {
        String prNumber = prUrl.substring(prUrl.lastIndexOf('/') + 1);
        restClient.post()
                .uri("/repos/{repository}/issues/{number}/comments", repository, prNumber)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", comment))
                .retrieve()
                .toBodilessEntity();
        log.info("Posted comment on PR '{}' in '{}'", prUrl, repository);
    }

    @Override
    public String pushAmendingCommit(String repository, String branchName, String commitMessage, String diff) {
        withClone(repository, branchName, false, branchName, git -> {
            applyPatchAndCommit(git, diff, commitMessage);
            pushBranch(git, branchName);
        });
        return findOpenPullRequestUrl(repository, branchName);
    }

    @Override
    public String readFile(String repository, String path, String ref) {
        String uri = ref == null
                ? "/repos/{repository}/contents/{path}"
                : "/repos/{repository}/contents/{path}?ref=" + ref;
        Map<String, Object> response = restClient.get()
                .uri(uri, repository, path)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        String base64Content = String.valueOf(response.get("content")).replace("\n", "");
        return new String(Base64.getDecoder().decode(base64Content), StandardCharsets.UTF_8);
    }

    @Override
    public List<RepositorySummary> listOrganizationRepositories(String organization) {
        List<Map<String, Object>> repos = restClient.get()
                .uri("/orgs/{organization}/repos?per_page=100", organization)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        if (repos == null) {
            return List.of();
        }
        return repos.stream()
                .map(r -> new RepositorySummary(
                        String.valueOf(r.get("full_name")),
                        r.get("description") == null ? "" : String.valueOf(r.get("description")),
                        String.valueOf(r.get("default_branch"))))
                .toList();
    }

    @Override
    public List<CodeSearchResult> searchCode(String query) {
        Map<String, Object> response = restClient.get()
                .uri("/search/code?q={query}&per_page=30", query)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = response == null
                ? List.of()
                : (List<Map<String, Object>>) response.getOrDefault("items", List.of());
        return items.stream()
                .map(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> repo = (Map<String, Object>) item.get("repository");
                    return new CodeSearchResult(
                            String.valueOf(repo.get("full_name")),
                            String.valueOf(item.get("path")),
                            String.valueOf(item.get("html_url")));
                })
                .toList();
    }

    private String defaultBranch(String repository) {
        Map<String, Object> repo = restClient.get()
                .uri("/repos/{repository}", repository)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return String.valueOf(repo.get("default_branch"));
    }

    private String findOpenPullRequestUrl(String repository, String branchName) {
        String owner = repository.substring(0, repository.indexOf('/'));
        List<Map<String, Object>> pulls = restClient.get()
                .uri("/repos/{repository}/pulls?state=open&head={owner}:{branch}", repository, owner, branchName)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        if (pulls == null || pulls.isEmpty()) {
            throw new IllegalStateException(
                    "No open pull request found for branch '%s' in '%s'".formatted(branchName, repository));
        }
        return String.valueOf(pulls.get(0).get("html_url"));
    }

    /** Clones {@code repository} at {@code checkoutRef} into a throwaway temp dir, runs {@code action}, then cleans up. */
    private void withClone(
            String repository, String checkoutRef, boolean createBranch, String workingBranch, GitAction action) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("github-client-clone");
            try (Git git = Git.cloneRepository()
                    .setURI(properties.cloneBaseUrl() + "/" + repository + ".git")
                    .setDirectory(workDir.toFile())
                    .setBranch(checkoutRef)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(properties.token(), ""))
                    .call()) {
                if (createBranch) {
                    git.branchCreate().setName(workingBranch).call();
                    git.checkout().setName(workingBranch).call();
                }
                action.run(git);
            }
        } catch (GitAPIException | IOException e) {
            throw new IllegalStateException("Git operation failed for repository '" + repository + "'", e);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void applyPatchAndCommit(Git git, String diff, String commitMessage) {
        try {
            git.apply().setPatch(new ByteArrayInputStream(diff.getBytes(StandardCharsets.UTF_8))).call();
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage(commitMessage)
                    .setAuthor(properties.author(), properties.authorEmail())
                    .call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Failed to apply patch/commit", e);
        }
    }

    private void pushBranch(Git git, String branchName) {
        try {
            git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(properties.token(), ""))
                    .setRefSpecs(new RefSpec(branchName + ":" + branchName))
                    .call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Failed to push branch '" + branchName + "'", e);
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file '{}'", p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface GitAction {
        void run(Git git) throws GitAPIException;
    }
}
