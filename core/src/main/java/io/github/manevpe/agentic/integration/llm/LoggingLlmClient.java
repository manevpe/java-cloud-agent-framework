package io.github.manevpe.agentic.integration.llm;

import io.github.manevpe.agentic.integration.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Placeholder {@link LlmClient} that logs instead of calling a real model.
 * Active by default ({@code agentic.llm.enabled} unset or {@code false}) —
 * safe for local dev/tests without model credentials configured. Mirrors
 * {@code LoggingGitHubClient}/{@code LoggingSandboxWorkspaceClient}'s
 * style: rather than there being no {@link LlmClient} bean at all when
 * disabled (which used to force every caller to keep a separate
 * heuristic-only implementation class around, e.g. {@code
 * HeuristicPlanningAssistant}), this stub lets every agent be a single
 * class that always depends on {@link LlmClient} and always builds the
 * same prompts/tools — the agent itself decides what a blank completion
 * means (typically: fall back to the same deterministic behaviour this
 * project had before any LLM integration existed).
 *
 * <p>Swap by enabling {@code agentic.llm.enabled=true}, which activates
 * {@code SpringAiLlmClient} instead.
 */
@Component
@ConditionalOnProperty(prefix = "agentic.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LoggingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingLlmClient.class);

    @Override
    public String complete(String systemPrompt, String userPrompt, List<ToolCallback> tools) {
        log.info("[stub] No LLM configured (agentic.llm.enabled=false) — skipping model call ({} tool(s) offered)",
                tools.size());
        return "";
    }
}
