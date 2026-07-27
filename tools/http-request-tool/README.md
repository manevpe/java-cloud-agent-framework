# http-request-tool

Bundle name: **`http-request`**

A generic, credential-free "fetch a URL" tool — e.g. for pages in a
team's internal documentation wiki (referenced from domain knowledge as a
plain URL) that aren't themselves a GitHub repository file readable via
`readRepoFile`.

## Tools exposed

| Tool | Parameters | Returns |
|---|---|---|
| `fetchUrl` | `url` (must start with `http://` or `https://`) | The response body as text, truncated to the first 20,000 characters (`MAX_RESPONSE_CHARS`), or `"(empty response body)"` if blank |

Deliberately narrow: **GET only**, no request body/headers/auth the model
can set, a hard response-size cap, and short timeouts (10s connect / 20s
read) so a single tool call can't hang or blow up context with a huge or
binary response.

## Rate limiting

Only **one request may be in flight at a time per calling agent turn** —
tracked via a `ThreadLocal<Semaphore>` (the same per-turn scoping
`workspace-setup-tool` uses for its sandbox-workspace cap). A second
`fetchUrl` call while one is still in flight on the same turn fails fast
with a tool error rather than firing concurrently.

## Used by

Not required by any built-in agent today — available to any agent that
adds `http-request` to its own `requiredTools()` or a workflow node adds
it to its `tools: [...]` config.

## Collaborators

**No `PluginContextAware` wiring** — unlike `GitHubClient`/`JiraClient`/
`SlackClient`, this tool has no credentials or swappable backend, so it's
the same real implementation in every environment (no `LoggingXClient`
stub needed for tests either). It implements `ToolBundle` directly with a
plain no-arg constructor.

## Dependencies

```kotlin
dependencies {
    compileOnly(project(":core"))
    implementation(project(":tools:tool-support"))
    implementation("org.springframework.ai:spring-ai-model")
    implementation("org.springframework:spring-web")

    testImplementation(project(":core"))
}
```

## Notes

- Not a substitute for `readRepoFile`/`gitClone` when reading files from a
  GitHub repository — those understand repository/path semantics and
  authentication; this tool is for arbitrary public (or
  already-authorized-by-network-context) pages only.
- Uses `tool-support`'s `ToolResults.orPlaceholder(...)` so an empty
  response body never comes back as a blank `String`.
