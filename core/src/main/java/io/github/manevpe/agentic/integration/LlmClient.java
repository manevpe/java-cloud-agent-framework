package io.github.manevpe.agentic.integration;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Generic port to a backing LLM: a system/user prompt pair in, raw text
 * completion out — optionally letting the model autonomously invoke tools
 * (e.g. reading a specific repo file) before producing that final text.
 * Deliberately carries no domain-specific concepts (plans, tickets, code,
 * ...) so it can be reused by any future agent/workflow — coding or not.
 * Domain-specific prompt templates, tool selection, and response parsing
 * belong one layer up (e.g. {@code agent.planning.LlmPlanningAssistant}),
 * never here.
 *
 * <p>{@link ToolCallback} is Spring AI's own provider-agnostic tool
 * abstraction (works identically across Vertex AI, OpenAI, Anthropic, ...)
 * — using it here doesn't reintroduce a provider dependency, the same way
 * depending on {@code ChatModel} doesn't.
 *
 * <p>The default configuration ({@code agentic.llm.enabled=false}) has no
 * bean for this port at all — callers that don't strictly need a real
 * model (like {@code PlanningAgent}'s default {@code
 * HeuristicPlanningAssistant}) simply don't depend on it, so no
 * credentials are required to run this project locally or in tests.
 * Enabling {@code agentic.llm.enabled=true} activates {@code
 * SpringAiLlmClient}, backed by whichever provider {@code
 * agentic.llm.provider} selects (see {@code GoogleGenAiAutoConfiguration}
 * for the default provider).
 */
public interface LlmClient {

    /** Sends a single system/user prompt pair and returns the model's raw text completion. */
    default String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, List.of());
    }

    /**
     * Sends a system/user prompt pair, allowing the model to autonomously
     * invoke any of the given tools (possibly more than once, in any
     * order) before producing its final text completion.
     */
    String complete(String systemPrompt, String userPrompt, List<ToolCallback> tools);
}
