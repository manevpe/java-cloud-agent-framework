package io.github.manevpe.agentic.integration;

import java.util.List;

/**
 * Port to GitHub. Kept narrow to what {@code CodeChangeService}/{@code
 * CodingAgent}/planning's repo-file-reading tool currently need: pushing a
 * branch and opening a pull request, replying to review comments, pushing
 * an amending commit onto an already-open PR's branch, reading a single
 * file's content, and two read-only discovery operations (listing an
 * organization's repositories, searching code across GitHub) that let an
 * LLM narrow down which repository is relevant to a task cheaply — no
 * cloning, no sandbox pod — before committing to a full {@code gitClone}
 * (see {@code GitHubApiTool}).
 */
public interface GitHubClient {

    /**
     * @return the URL of the opened pull request
     */
    String pushBranchAndOpenPullRequest(
            String repository, String branchName, String commitMessage,
            String diff, String prTitle, String prDescription);

    /** Posts a reply comment on an already-open pull request. */
    void postPullRequestComment(String repository, String prUrl, String comment);

    /**
     * Pushes an additional commit onto an existing PR's branch (e.g. in
     * response to review feedback).
     *
     * @return the URL of the amended pull request (typically unchanged
     *         from the original PR URL)
     */
    String pushAmendingCommit(String repository, String branchName, String commitMessage, String diff);

    /**
     * Reads a single file's content from a repository — the primitive
     * behind the planning agent's repo-file-reading tool (see {@code
     * FileReadTool}), letting an LLM inspect existing code before
     * finalizing an implementation plan.
     *
     * @param repository {@code owner/repo}
     * @param path       path to the file within the repository
     * @param ref        branch/tag/commit to read from; {@code null} means the default branch
     */
    String readFile(String repository, String path, String ref);

    /**
     * Lists repositories belonging to a GitHub organization (or user) —
     * a cheap way for an LLM to see what repositories actually exist
     * (with their descriptions) before guessing which one a task
     * belongs to.
     *
     * @param organization GitHub organization or user login
     */
    List<RepositorySummary> listOrganizationRepositories(String organization);

    /**
     * Searches code across GitHub using its code-search API — another
     * cheap, no-cloning way for an LLM to locate the right repository or
     * file (e.g. searching for a class/config-key name it expects to
     * exist) before committing to a full {@code gitClone}.
     *
     * @param query a GitHub code-search query, e.g. {@code "ReportType
     *              org:paymenttools"} — see
     *              <a href="https://docs.github.com/en/search-github/searching-on-github/searching-code">
     *              GitHub's code-search syntax</a>
     */
    List<CodeSearchResult> searchCode(String query);

    /** One repository's key discovery-relevant fields. */
    record RepositorySummary(String fullName, String description, String defaultBranch) {
    }

    /** One code-search hit: which repository/file matched. */
    record CodeSearchResult(String repository, String path, String url) {
    }
}
