package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code agentic.llm.github-models.*} properties — only consulted
 * when {@code agentic.llm.provider=github-models}. GitHub Models
 * (<a href="https://models.github.ai">models.github.ai</a>) is GitHub's
 * officially supported, OpenAI-API-compatible inference endpoint,
 * authenticated with a GitHub personal access token that has the
 * {@code models: read} permission. Deliberately distinct from — and not to
 * be confused with — GitHub Copilot's own internal completions endpoint,
 * which is not an officially supported third-party/backend API and whose
 * terms of use restrict it to IDE-integrated clients.
 *
 * @param token GitHub PAT with {@code models: read} permission
 * @param baseUrl the GitHub Models inference base URL
 * @param model the model catalog id, e.g. {@code openai/gpt-4o}
 */
@ConfigurationProperties(prefix = "agentic.llm.github-models")
public record GitHubModelsProperties(
        String token,
        String baseUrl,
        String model
) {
    public GitHubModelsProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://models.github.ai/inference" : baseUrl;
        model = (model == null || model.isBlank()) ? "openai/gpt-4o" : model;
    }
}
