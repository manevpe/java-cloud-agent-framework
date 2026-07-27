package io.github.manevpe.agentic.integration.llm.tool;

import io.github.manevpe.agentic.integration.GitHubClient;
import io.github.manevpe.agentic.plugin.PluginContext;
import io.github.manevpe.agentic.plugin.PluginContextAware;
import io.github.manevpe.agentic.tool.ToolBundle;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * Exposes {@link GitHubClient}'s read-only discovery operations —
 * listing an organization's repositories and searching code across
 * GitHub — as LLM-invocable tools. Neither operation clones anything or
 * opens a sandbox pod, so a model uncertain which repository a task
 * belongs to should use these (and {@code readRepoFile}) to narrow down
 * the answer before ever calling {@code gitClone} — see {@code
 * WorkspaceSetupTool}'s per-turn cap on distinct sandbox workspaces,
 * which this tool exists specifically to make unnecessary to hit.
 */
public class GitHubApiTool implements ToolBundle, PluginContextAware {

    private GitHubClient gitHubClient;

    /** No-arg constructor for {@code ServiceLoader} discovery — see {@link #setPluginContext}. */
    public GitHubApiTool() {
    }

    public GitHubApiTool(GitHubClient gitHubClient) {
        this.gitHubClient = gitHubClient;
    }

    @Override
    public void setPluginContext(PluginContext context) {
        this.gitHubClient = context.gitHubClient();
    }

    @Override
    public String name() {
        return "github-api";
    }

    @Override
    public List<ToolCallback> tools() {
        return List.of(ToolCallbacks.from(this));
    }

    @Tool(description = "Lists the repositories in a GitHub organization (name + description + default "
            + "branch), so you can see what actually exists instead of guessing a repository name. Cheap: "
            + "does not clone anything or open a sandbox workspace.")
    public List<GitHubClient.RepositorySummary> listGithubOrgRepositories(
            @ToolParam(description = "GitHub organization or user login, e.g. 'my-org'") String organization) {
        return gitHubClient.listOrganizationRepositories(organization);
    }

    @Tool(description = "Searches code across GitHub (e.g. for a class name, config key, or file you expect "
            + "to exist) and returns matching repository/file/URL hits, so you can locate the right "
            + "repository before cloning it. Cheap: does not clone anything or open a sandbox workspace. "
            + "Use GitHub's code-search syntax, e.g. 'ReportType org:my-org' or 'filename:build.gradle.kts'.")
    public List<GitHubClient.CodeSearchResult> searchGithubCode(
            @ToolParam(description = "a GitHub code-search query") String query) {
        return gitHubClient.searchCode(query);
    }
}
