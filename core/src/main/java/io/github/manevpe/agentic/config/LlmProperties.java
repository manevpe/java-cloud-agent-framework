package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code agentic.llm.*} application properties controlling which LLM
 * provider (if any) backs the real {@code SpringAiLlmClient}.
 *
 * <p>This project's {@code LlmClient} port and its {@code SpringAiLlmClient}
 * implementation depend only on Spring AI's provider-agnostic
 * {@code ChatModel} interface — never on a specific provider's classes.
 * {@code provider} just selects which provider-specific {@code @Configuration}
 * (e.g. {@code GoogleGenAiAutoConfiguration}) supplies that
 * {@code ChatModel} bean, mirroring the project's DB-agnostic persistence
 * approach: swapping LLM providers means adding one new small
 * provider-specific config class, not touching the port or its generic
 * adapter.
 *
 * <p>Every provider's own Spring AI starter autoconfiguration is
 * unconditionally excluded (see {@code application.yml}'s
 * {@code spring.autoconfigure.exclude}) because those eagerly build a
 * {@code ChatModel} bean at context-startup time and fail hard without
 * provider credentials configured — which would break every
 * {@code @SpringBootTest} in this codebase merely by having a starter on
 * the classpath. Instead, this project's provider-specific configs build
 * the {@code ChatModel} bean themselves, gated behind
 * {@code agentic.llm.enabled}, mirroring {@code SandboxAutoConfiguration}'s
 * pattern for the Kubernetes client.
 *
 * @param enabled  when {@code false} (the default), a deterministic logging stub is
 *                 used instead of calling a real model — safe for local dev/tests
 *                 without provider credentials available
 * @param provider which provider-specific {@code ChatModel} config to activate
 *                 (currently {@code google-genai} or {@code github-models}
 *                 are implemented)
 */
@ConfigurationProperties(prefix = "agentic.llm")
public record LlmProperties(
        boolean enabled,
        String provider
) {
    public LlmProperties {
        provider = (provider == null || provider.isBlank()) ? "google-genai" : provider;
    }
}
