package io.github.manevpe.agentic.controller.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.manevpe.agentic.integration.LlmClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only {@link LlmClient} that answers every prompt shape the agents
 * in this workflow send — {@code PlanningAgent}'s plan-draft prompt
 * (plain text starting with a {@code STATUS: READY}/{@code STATUS:
 * NEEDS_CLARIFICATION} marker line), {@code ConversationalPlanningAgent}'s
 * conversation-session plan-draft prompt (plain text, or an {@code
 * askHuman} tool call), {@code CodingAgent}'s code-implementation prompt
 * (a simulated {@code submitImplementationResult} tool call), and its
 * PR-review-response prompt (a simulated {@code submitPrResponse} tool
 * call) — without calling any real model, so end-to-end flow tests can
 * exercise the real agent code paths (parsing, GitHub push) with a
 * deterministic, in-process substitute.
 *
 * <p>Rather than being constructor-injected with the agents' own tool
 * instances (no longer possible now that {@code AskHumanTool}/{@code
 * SubmitImplementationResultTool}/{@code SubmitPrResponseTool} are plain
 * objects each agent constructs for itself inside {@code
 * setPluginContext}, not Spring beans — see ADR-0007), this invokes the
 * exact {@link ToolCallback}s passed into {@link #complete}, which are
 * always bound to that specific agent instance's own tool objects. This
 * is actually a more faithful simulation of a real model's tool-calling
 * loop than the earlier direct-bean-call approach.
 *
 * <p>Always reports a ready-to-implement plan and a successful
 * implementation (mirroring what a real model would return once its
 * build/test command passes), and reproduces the project's original
 * keyword-matching heuristic for the PR-review decision, so review
 * comments asking for a change ("please", "fix", ...) still trigger an
 * amendment and complimentary reviews don't.
 */
public class FakeLlmClient implements LlmClient {

    /** Ticket descriptions containing this marker trigger one simulated askHuman round. */
    public static final String ASK_ONCE_MARKER = "SIMULATE_ASK_ONCE";
    private static final String SIMULATED_QUESTION = "Which metric should the widget show?";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String complete(String systemPrompt, String userPrompt, List<ToolCallback> tools) {
        if (systemPrompt.contains("askHuman tool")) {
            return conversationalPlanResponse(userPrompt, tools);
        }
        if (systemPrompt.contains("STATUS: READY")) {
            return planDraftResponse(userPrompt);
        }
        if (systemPrompt.contains("submitImplementationResult")) {
            return codeImplementationResponse(tools);
        }
        if (systemPrompt.contains("submitPrResponse")) {
            return prResponseDecision(userPrompt, tools);
        }
        throw new IllegalStateException("FakeLlmClient received a prompt it doesn't know how to answer: " + systemPrompt);
    }

    private static String planDraftResponse(String userPrompt) {
        return "STATUS: READY\nPlan drafted by FakeLlmClient for:\n" + userPrompt;
    }

    /**
     * Simulates one round of {@code askHuman} the very first time this
     * conversation runs (transcript still empty) for a ticket whose
     * description contains {@value #ASK_ONCE_MARKER}; every later round
     * (once the human's reply shows up in the transcript) drafts a final
     * plan instead — invoking the {@code askHuman} tool callback directly,
     * exactly as Spring AI would when the model itself calls the tool.
     */
    private static String conversationalPlanResponse(String userPrompt, List<ToolCallback> tools) {
        boolean firstRound = userPrompt.contains("(conversation not started yet)");
        if (firstRound && userPrompt.contains(ASK_ONCE_MARKER)) {
            invokeTool(tools, "askHuman", Map.of("question", SIMULATED_QUESTION));
            return "";
        }
        return "Conversational plan drafted by FakeLlmClient for:\n" + userPrompt;
    }

    /**
     * Simulates the model calling {@code submitImplementationResult}
     * directly against the real tool callback, exactly as Spring AI would.
     */
    private static String codeImplementationResponse(List<ToolCallback> tools) {
        invokeTool(tools, "submitImplementationResult", Map.of(
                "repository", "example-org/example-repo",
                "testsPassed", true,
                "diff", "diff --git a/Widget.java b/Widget.java",
                "testSummary", "12 tests passed",
                "changedFiles", List.of("src/main/java/com/example/Widget.java")));
        return "";
    }

    /**
     * Simulates the model calling {@code submitPrResponse} directly
     * against the real tool callback, exactly as Spring AI would.
     */
    private static String prResponseDecision(String userPrompt, List<ToolCallback> tools) {
        boolean needsAmendment = requestsCodeChange(userPrompt);
        String reply = needsAmendment
                ? "Thanks for the review — addressing it in one commit."
                : "Thanks for the review — noted, no code changes needed.";
        invokeTool(tools, "submitPrResponse", Map.of("needsAmendment", needsAmendment, "reply", reply));
        return "";
    }

    private static boolean requestsCodeChange(String userPrompt) {
        String lower = userPrompt.toLowerCase();
        return lower.contains("please") || lower.contains("could you")
                || lower.contains("can you") || lower.contains("fix")
                || lower.contains("change") || lower.contains("update");
    }

    private static void invokeTool(List<ToolCallback> tools, String toolName, Map<String, Object> arguments) {
        ToolCallback callback = findTool(tools, toolName);
        try {
            callback.call(OBJECT_MAPPER.writeValueAsString(arguments));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to invoke tool '" + toolName + "'", e);
        }
    }

    private static ToolCallback findTool(List<ToolCallback> tools, String toolName) {
        Optional<ToolCallback> found = tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(toolName))
                .findFirst();
        return found.orElseThrow(() -> new IllegalStateException(
                "FakeLlmClient expected a '" + toolName + "' tool among: "
                        + tools.stream().map(t -> t.getToolDefinition().name()).toList()));
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public LlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }
}
