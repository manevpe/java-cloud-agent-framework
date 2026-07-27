# github-api-tool

Bundle name: **`github-api`**

Read-only GitHub *discovery* — lists an organization's repositories and
searches code across GitHub — so a model can figure out which repository
a task belongs to before paying the cost of a full `gitClone` into a
sandbox pod (see [`workspace-setup-tool`](../workspace-setup-tool/README.md),
which caps how many distinct sandbox workspaces one turn may open).

## Tools exposed

| Tool | Parameters | Returns |
|---|---|---|
| `listGithubOrgRepositories` | `organization` (org/user login, e.g. `my-org`) | `List<GitHubClient.RepositorySummary>` — name, description, default branch, for every repo in the org |
| `searchGithubCode` | `query` (GitHub code-search syntax, e.g. `ReportType org:my-org` or `filename:build.gradle.kts`) | `List<GitHubClient.CodeSearchResult>` — matching repository/file/URL hits |

Both delegate directly to `GitHubClient#listOrganizationRepositories` /
`GitHubClient#searchCode`.

## Used by

- `coding-agent` — narrows down which repository an implementation task
  or PR-review-response belongs to, especially when the node's workflow
  config doesn't pin a `repository` value (see `CodingAgent`'s Javadoc on
  its `implement` mode).

## Collaborators

Implements `PluginContextAware`; `setPluginContext` wires in
`context.gitHubClient()`. A `GitHubApiTool(GitHubClient)` constructor is
also available for direct construction outside the `ServiceLoader`/
`PluginContext` path.

## Dependencies

```kotlin
dependencies {
    compileOnly(project(":core"))
    implementation(project(":tools:tool-support"))
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
```

## Notes

- Neither tool clones anything or opens a sandbox workspace — always
  cheaper than `gitClone` for narrowing down a repository. A model
  uncertain which repository a task belongs to should exhaust these (and
  `file-read-tool`'s `readRepoFile`) before ever calling `gitClone`.
