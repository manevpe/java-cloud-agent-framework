package io.github.manevpe.agentic.integration.llm;

import io.github.manevpe.agentic.integration.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real {@link LlmClient} implementation, active once a {@link ChatModel}
 * bean exists (see {@code GoogleGenAiAutoConfiguration} — or any future
 * sibling config for another provider). Deliberately depends only on
 * Spring AI's provider-agnostic {@link ChatModel}/{@link ChatClient}/
 * {@link ToolCallback} abstractions, never on a specific provider's
 * classes, so swapping providers never requires changing this class —
 * only which {@code @Configuration} supplies the {@link ChatModel} bean.
 * Carries no domain-specific prompt or tool content of its own; callers
 * own their prompts and tools.
 *
 * <p>Every call's prompt and the model's full response — plus call
 * duration, the reporting provider/model name, and (when the provider
 * reports it) prompt/completion/total token usage — are logged at INFO;
 * a failed call is logged at WARN with the elapsed time and exception
 * before being rethrown. This is the one place every LLM "thought" (and
 * every retry the provider's {@code ChatModel} bean silently performs
 * under the hood, e.g. {@code GoogleGenAiAutoConfiguration}'s 429-backoff
 * retry, which is otherwise invisible above DEBUG — see that class's
 * {@code LoggingRetryListener}) passes through, so logging here (rather
 * than in each calling agent) captures every model interaction, across
 * every agent, for free. Combined with the {@code
 * threadId}/{@code traceId}/{@code workflowId} MDC values set for the
 * duration of a workflow invocation (see {@code
 * WorkflowEngineService#invoke}), these log lines land in the structured
 * trace log file ({@code logging.file.name} in {@code application.yml})
 * already correlated to the workflow run that produced them.
 *
 * <p>When the configured model supports Gemini's "thinking" feature and
 * {@code agentic.llm.google-genai.include-thoughts=true} is set (see
 * {@code GoogleGenAiAutoConfiguration}), the model's response is split
 * into two log lines matching the two-tier distinction Copilot CLI itself
 * shows: an "LLM reasoning (interim, not the final answer)" line carrying
 * its unsure/in-progress thought summary, and the usual "LLM response"
 * line carrying only its final answer text — which is also all that is
 * returned to the caller, so agents never see reasoning text mixed into
 * the content they parse. With no thinking support/opt-in, this is a
 * no-op and behaves exactly as before.
 *
 * <p>Multi-round agents ({@code PlanningAgent}'s clarification loop, {@code
 * ConversationalPlanningAgent}'s {@code askHuman} conversation) rebuild
 * their full prompt from scratch on every round, growing one history/
 * transcript block in place while the surrounding context (ticket
 * summary/description, knowledge-source content, static instructions)
 * stays identical — so logging the same full prompt every round would
 * repeat that (potentially large, e.g. imported knowledge-source
 * documents) unchanged context on every single call. To avoid that,
 * {@link #logRequest} keeps the last prompt logged per workflow trace
 * ({@code traceId}, from MDC) and logs only the prompt's first call in
 * full; every subsequent call logs just the text between the longest
 * common prefix and longest common suffix shared with the previous
 * prompt — i.e. only the newest round's content, wherever in the prompt
 * it was inserted, with zero knowledge here of what a "round" even is.
 */
@Component
@ConditionalOnBean(ChatModel.class)
@ConditionalOnProperty(prefix = "agentic.llm", name = "enabled", havingValue = "true")
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);

    /** Bounds unbounded growth across many workflow runs — see {@link #logRequest}. */
    private static final int MAX_TRACKED_TRACES = 1_000;

    private final ChatClient chatClient;

    // Evicts the least-recently-used trace once full, rather than requiring
    // every caller to explicitly signal "this workflow run is done" back
    // into this generic, domain-agnostic port.
    private final Map<String, String> lastLoggedPromptByTrace = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_TRACKED_TRACES;
                }
            });

    /**
     * Takes the Spring-managed {@link ChatClient.Builder} bean (not {@code
     * ChatClient.builder(chatModel)}) specifically so its auto-registered
     * {@code ToolCallingAdvisor} is wired with the application's {@code
     * ToolExecutionExceptionProcessor} bean — see {@code
     * ToolExecutionErrorHandlingConfiguration} — rather than Spring AI's
     * un-configurable static default. Using the static factory here would
     * silently bypass that bean and reintroduce the corrupted-tool-call-
     * history bug it fixes.
     */
    public SpringAiLlmClient(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, List<ToolCallback> tools) {
        logRequest(systemPrompt, userPrompt, tools);

        long startNanos = System.nanoTime();
        ChatResponse chatResponse;
        try {
            chatResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .toolCallbacks(tools)
                    .call()
                    .chatResponse();
        } catch (RuntimeException e) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("LLM call failed after {}ms — {}: {}", elapsedMs, e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        String thoughts = thoughtSummaryText(chatResponse);
        if (!thoughts.isBlank()) {
            // Interim, unsure reasoning the model chose to surface (only
            // populated when the configured model supports "thinking" and
            // agentic.llm.google-genai.include-thoughts=true) — analogous
            // to Copilot CLI's own distinction between in-progress
            // reasoning and its final message. Logged separately, and
            // excluded from the returned answer text below, so callers
            // keep receiving only the final content they've always
            // received.
            log.info("LLM reasoning (interim, not the final answer) in {}ms:\n{}", elapsedMs, thoughts);
        }

        String response = finalAnswerText(chatResponse);
        log.info("LLM response ({}) in {}ms:\n{}", usageSummary(chatResponse), elapsedMs, response);
        return response;
    }

    /**
     * Concatenates the text of every non-thought {@link Generation} in the
     * response — normally just one, but Gemini "thinking" models can split
     * a single call's output across several parts/generations, each
     * flagged via the {@code isThought} metadata property set by Spring
     * AI's Google GenAI integration (only present when {@code
     * agentic.llm.google-genai.include-thoughts=true}; absent — treated as
     * {@code false} — for every other provider/config, in which case this
     * is equivalent to the single {@link ChatResponse#getResult()} call
     * used before thought-summary support was added).
     */
    private static String finalAnswerText(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return "";
        }
        return chatResponse.getResults().stream()
                .filter(generation -> !isThought(generation))
                .map(generation -> generation.getOutput().getText())
                .filter(text -> text != null && !text.isEmpty())
                .reduce("", (a, b) -> a + b);
    }

    /** The counterpart of {@link #finalAnswerText}: only the thought-flagged generations' text. */
    private static String thoughtSummaryText(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return "";
        }
        return chatResponse.getResults().stream()
                .filter(SpringAiLlmClient::isThought)
                .map(generation -> generation.getOutput().getText())
                .filter(text -> text != null && !text.isEmpty())
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();
    }

    private static boolean isThought(Generation generation) {
        Object flag = generation.getOutput().getMetadata().get("isThought");
        return Boolean.TRUE.equals(flag);
    }

    /**
     * Renders the model name and token usage (prompt/completion/total)
     * reported by the provider for this call, e.g. {@code "model:
     * gemini-flash-lite-latest, tokens: 1234 in / 56 out (1290 total)"} —
     * or a placeholder if the provider didn't report usage metadata (some
     * providers/errors omit it). Surfacing this per call makes quota
     * consumption visible in the logs as it happens, rather than only
     * discovering it after a 429 already occurred.
     */
    private static String usageSummary(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return "no response metadata";
        }
        String model = chatResponse.getMetadata().getModel();
        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage == null) {
            return "model: %s, tokens: unavailable".formatted(model);
        }
        return "model: %s, tokens: %s in / %s out (%s total)".formatted(
                model, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    private void logRequest(String systemPrompt, String userPrompt, List<ToolCallback> tools) {
        String fullPrompt = systemPrompt + "\n---\n" + userPrompt;
        String toolNames = tools.stream().map(t -> t.getToolDefinition().name()).toList().toString();
        String traceId = MDC.get("traceId");

        if (traceId == null) {
            // No workflow trace context (e.g. a direct/test call) — nothing
            // to diff against, always log in full.
            log.info("LLM request (no trace context) — {} tool(s) offered {}:\n{}",
                    tools.size(), toolNames, fullPrompt);
            return;
        }

        String previous = lastLoggedPromptByTrace.put(traceId, fullPrompt);
        if (previous == null) {
            log.info("LLM request (trace '{}', first call) — {} tool(s) offered {}:\n{}",
                    traceId, tools.size(), toolNames, fullPrompt);
            return;
        }

        String newPart = newContentSince(previous, fullPrompt);
        if (newPart.isBlank()) {
            log.info("LLM request (trace '{}') — identical prompt to the previous call, {} tool(s) offered {}",
                    traceId, tools.size(), toolNames);
        } else {
            log.info("LLM request (trace '{}', new content since previous call) — {} tool(s) offered {}:\n{}",
                    traceId, tools.size(), toolNames, newPart);
        }
    }

    /**
     * Returns just the substring of {@code next} that changed relative to
     * {@code previous}, trimming both the longest common prefix and the
     * longest common suffix the two strings share. Multi-round prompts
     * grow a single history/transcript block in the middle of an
     * otherwise-static template, so this isolates that newest round's
     * content without needing to know the template's structure.
     */
    private static String newContentSince(String previous, String next) {
        int maxPrefix = Math.min(previous.length(), next.length());
        int prefixLen = 0;
        while (prefixLen < maxPrefix && previous.charAt(prefixLen) == next.charAt(prefixLen)) {
            prefixLen++;
        }

        int maxSuffix = maxPrefix - prefixLen;
        int suffixLen = 0;
        while (suffixLen < maxSuffix
                && previous.charAt(previous.length() - 1 - suffixLen) == next.charAt(next.length() - 1 - suffixLen)) {
            suffixLen++;
        }

        return next.substring(prefixLen, next.length() - suffixLen);
    }
}

