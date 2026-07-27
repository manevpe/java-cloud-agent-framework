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
 * Exposes {@link GitHubClient#readFile} as an LLM-invocable tool via
 * Spring AI's {@link Tool @Tool} annotation — see {@code
 * LlmPlanningAssistant}, which offers this to the model so it can inspect
 * existing repository code before finalizing an implementation plan,
 * rather than being limited to whatever context was pre-fetched into the
 * prompt. Reusable by any future agent that also wants to let the model
 * pull repo file content on demand.
 */
public class FileReadTool implements ToolBundle, PluginContextAware {

    private GitHubClient gitHubClient;

    /** No-arg constructor for {@code ServiceLoader} discovery — see {@link #setPluginContext}. */
    public FileReadTool() {
    }

    public FileReadTool(GitHubClient gitHubClient) {
        this.gitHubClient = gitHubClient;
    }

    @Override
    public void setPluginContext(PluginContext context) {
        this.gitHubClient = context.gitHubClient();
    }

    @Override
    public String name() {
        return "file-read";
    }

    @Override
    public List<ToolCallback> tools() {
        return List.of(ToolCallbacks.from(this));
    }

    @Tool(description = "Reads the content of a specific file from a specific GitHub repository, "
            + "so you can inspect existing code before finalizing an implementation plan.")
    public String readRepoFile(
            @ToolParam(description = "the repository in 'owner/repo' form") String repository,
            @ToolParam(description = "path to the file within the repository, e.g. src/main/java/com/acme/Foo.java")
            String path) {
        return ToolResults.orPlaceholder(gitHubClient.readFile(repository, path, null), "(file is empty)");
    }
}

