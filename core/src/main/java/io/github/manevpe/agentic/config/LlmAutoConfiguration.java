package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the provider-agnostic {@code agentic.llm.*} property groups.
 * Provider-specific {@code ChatModel} wiring (e.g. Vertex AI Gemini) lives
 * in its own sibling {@code @Configuration} — see {@code
 * GoogleGenAiAutoConfiguration} — so swapping providers never touches
 * this class.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmAutoConfiguration {
}
