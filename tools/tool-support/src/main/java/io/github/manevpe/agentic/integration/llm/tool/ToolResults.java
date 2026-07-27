package io.github.manevpe.agentic.integration.llm.tool;

/**
 * Guards {@code @Tool}-annotated methods that return a plain {@link
 * String} against ever returning a blank/empty one.
 *
 * <p>Spring AI's {@code DefaultToolCallResultConverter} serializes a tool's
 * {@code String} return value via {@code JsonHelper.toJson(result, true)},
 * which forwards a result as-is (without JSON-quoting it) whenever it is
 * already "valid JSON" per {@code JsonMapper.readTree}. Jackson 3's {@code
 * readTree("")} does not throw, so an empty string is (incorrectly, from
 * this integration's point of view) treated as already-valid JSON and
 * forwarded verbatim as the literal empty string. That empty content then
 * becomes the tool's {@code FunctionResponse} data sent to Gemini; on the
 * very next round, {@code GoogleGenAiChatModel} rebuilds the conversation
 * history from scratch and calls {@code parseJsonToMap("")} on it, which
 * throws {@code RuntimeException: Failed to parse JSON: } (empty input) —
 * failing the entire in-flight tool-calling round unrecoverably. This is a
 * known, still-open upstream bug in the same family as
 * <a href="https://github.com/spring-projects/spring-ai/issues/4556">spring-ai#4556</a>
 * (Gemini tool-calling choking on empty message/response content).
 *
 * <p>Since we control every {@code @Tool} method's return value, the
 * simplest reliable fix is entirely on our side: never let a tool's
 * {@code String} result be blank. Every {@code @Tool} method in this
 * package that can legitimately produce an empty result (an empty file,
 * no pending changes, ...) must route its return value through {@link
 * #orPlaceholder(String, String)} before returning.
 */
public final class ToolResults {

    private ToolResults() {
    }

    /**
     * Returns {@code value} unchanged unless it is {@code null} or blank,
     * in which case {@code placeholder} is returned instead — a
     * human-readable, non-empty stand-in the model can safely receive
     * (and safely have echoed back in conversation history) instead of
     * literal empty content.
     */
    public static String orPlaceholder(String value, String placeholder) {
        return (value == null || value.isBlank()) ? placeholder : value;
    }
}
