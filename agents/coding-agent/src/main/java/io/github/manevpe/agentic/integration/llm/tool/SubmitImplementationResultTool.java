package io.github.manevpe.agentic.integration.llm.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Optional;

/**
 * Lets {@code CodingAgent} get its final implementation result back as a
 * schema-enforced tool call rather than a hand-written JSON blob in the
 * completion text. {@code diff}/{@code testSummary} are large, code-heavy
 * free text mixed with genuinely separate fields ({@code testsPassed},
 * {@code changedFiles}) — exactly the shape that broke plain
 * JSON-in-completion parsing in practice (the model failed to escape a
 * long {@code planText}-shaped field correctly). Delegating the argument
 * encoding to the model provider's own function-calling layer (see Spring
 * AI's {@code ToolCallback}) avoids that fragility, the same way {@link
 * AskHumanTool} already does for human-in-the-loop questions.
 *
 * <p>Same per-call thread-local capture pattern as {@link AskHumanTool}:
 * Spring AI invokes tool methods synchronously on the same thread that
 * called {@code LlmClient#complete}, so the owning agent calls {@link
 * #consumeResult()} right after that call returns.
 */
public class SubmitImplementationResultTool {

    private final ThreadLocal<Result> result = new ThreadLocal<>();

    @Tool(description = "Submit the final result of implementing this code change. Call this exactly once, "
            + "after you have finished writing the change and running the repository's build/test command "
            + "(or determined it cannot pass). Do not produce any other text or tool calls in the same turn "
            + "as this call.")
    public String submitImplementationResult(
            @ToolParam(description = "the repository (in 'owner/repo' form) you cloned and implemented "
                    + "the change in, e.g. the value you passed to gitClone") String repository,
            @ToolParam(description = "whether the build/test command passed after your change") boolean testsPassed,
            @ToolParam(description = "the unified diff of your change") String diff,
            @ToolParam(description = "human-readable summary of the build/test result") String testSummary,
            @ToolParam(description = "repository-relative paths of every file you created, updated, or deleted")
            List<String> changedFiles) {
        result.set(new Result(repository, testsPassed, diff, testSummary, changedFiles == null ? List.of() : changedFiles));
        return "Implementation result recorded.";
    }

    /**
     * Returns (and clears) the result submitted during the current
     * thread's most recent {@code LlmClient#complete} call, if any.
     */
    public Optional<Result> consumeResult() {
        Result r = result.get();
        result.remove();
        return Optional.ofNullable(r);
    }

    public record Result(String repository, boolean testsPassed, String diff, String testSummary, List<String> changedFiles) {
    }
}
