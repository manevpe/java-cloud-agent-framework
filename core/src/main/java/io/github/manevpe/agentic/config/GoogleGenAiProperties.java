package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code agentic.llm.google-genai.*} properties — only consulted when
 * {@code agentic.llm.provider=google-genai} (the default). Backs Spring AI's
 * Google GenAI module (formerly the dedicated {@code vertex-ai-gemini}
 * module, renamed/merged upstream in Spring AI 2.0 — see
 * {@link GoogleGenAiAutoConfiguration}'s Javadoc), which supports two
 * distinct Gemini backends via the same {@code com.google.genai.Client},
 * selected by which properties are supplied here:
 * <ul>
 *   <li><b>Gemini Developer API / Google AI Studio</b> — set {@code apiKey}
 *       only (a free key from
 *       <a href="https://aistudio.google.com/apikey">aistudio.google.com</a>,
 *       no GCP project/billing/IAM required). Preferred when set.</li>
 *   <li><b>Vertex AI</b> — leave {@code apiKey} blank and set
 *       {@code projectId} (+ optionally {@code location}); requires
 *       Application Default Credentials and IAM permission on that GCP
 *       project.</li>
 * </ul>
 * Kept separate from the provider-agnostic {@link LlmProperties} so other
 * providers can add their own equally-isolated properties records later
 * without touching this one.
 *
 * @param apiKey    Google AI Studio API key; when non-blank, selects the
 *                  Gemini Developer API backend instead of Vertex AI
 * @param projectId GCP project id hosting the Vertex AI Gemini endpoint
 *                  (ignored when {@code apiKey} is set)
 * @param location  Vertex AI region (e.g. {@code us-central1}; ignored when
 *                  {@code apiKey} is set)
 * @param model     Gemini model name (e.g. {@code gemini-flash-lite-latest})
 * @param includeThoughts whether to ask the model to return "thought
 *                  summaries" (its interim, unsure reasoning, as opposed
 *                  to its final answer) alongside the response — see
 *                  {@link GoogleGenAiAutoConfiguration}'s Javadoc. Only
 *                  Gemini's "thinking" model family (2.5+/3.x, including
 *                  {@code gemini-flash-lite-latest}) supports this; the
 *                  Gemini API rejects the request with an error if set on
 *                  a non-thinking model such as {@code gemini-2.0-flash}
 *                  (the production default), so this defaults to
 *                  {@code false} and must be opted into per environment.
 */
@ConfigurationProperties(prefix = "agentic.llm.google-genai")
public record GoogleGenAiProperties(
        String apiKey,
        String projectId,
        String location,
        String model,
        Boolean includeThoughts
) {
    public GoogleGenAiProperties {
        location = (location == null || location.isBlank()) ? "us-central1" : location;
        model = (model == null || model.isBlank()) ? "gemini-flash-lite-latest" : model;
        includeThoughts = includeThoughts != null && includeThoughts;
    }

    public boolean usesApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
