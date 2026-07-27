package io.github.manevpe.agentic.integration.llm.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Optional;

/**
 * Lets {@code CodingAgent} get its PR-review-response decision back as a
 * schema-enforced tool call instead of a hand-written {@code
 * {"needsAmendment": ..., "reply": "..."}} JSON blob — same rationale and
 * per-call thread-local capture pattern as {@link
 * SubmitImplementationResultTool}/{@link AskHumanTool}.
 */
public class SubmitPrResponseTool {

    private final ThreadLocal<Result> result = new ThreadLocal<>();

    @Tool(description = "Submit your triage decision for this batch of PR review comments. Call this exactly "
            + "once, after deciding whether the batch genuinely requires a code change. Do not produce any "
            + "other text or tool calls in the same turn as this call.")
    public String submitPrResponse(
            @ToolParam(description = "whether this batch genuinely requires a code change") boolean needsAmendment,
            @ToolParam(description = "a short, professional reply to post on the PR") String reply) {
        result.set(new Result(needsAmendment, reply));
        return "PR response decision recorded.";
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

    public record Result(boolean needsAmendment, String reply) {
    }
}
