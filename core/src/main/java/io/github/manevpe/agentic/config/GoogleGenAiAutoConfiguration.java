package io.github.manevpe.agentic.config;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/**
 * Supplies the real {@link ChatModel} bean for the {@code google-genai}
 * provider — the only piece of this LLM integration that imports any
 * Google-GenAI-specific class. {@code SpringAiLlmClient} (the
 * {@code LlmClient} implementation actually used by {@code PlanningAgent})
 * depends only on the resulting provider-agnostic {@link ChatModel} bean,
 * so adding a different provider later means adding a sibling
 * {@code @Configuration} like this one — not touching
 * {@code SpringAiLlmClient} or the {@code LlmClient} port.
 *
 * <p>Named after Spring AI 2.0's "Google GenAI" module, which replaced the
 * previous dedicated {@code vertex-ai-gemini} module with a unified client
 * ({@code com.google.genai.Client}) supporting two backends from the same
 * SDK: the free Gemini Developer API (Google AI Studio, selected via
 * {@link Client.Builder#apiKey(String)}) and Vertex AI (selected via
 * {@link Client.Builder#vertexAI(boolean)} plus a GCP project/location).
 * {@link GoogleGenAiProperties#usesApiKey()} picks between them at startup
 * so both auth modes are supported without a separate provider identity.
 *
 * <p>The Gemini Developer API free tier enforces per-minute token/request
 * quotas (e.g. 250,000 input tokens/minute) that a multi-round tool-calling
 * agent conversation can burst through within a single minute — a
 * transient {@code 429} that clears once the per-minute window resets.
 * {@link #retryTemplate()} retries only on HTTP 429 responses
 * ({@link ApiException#code()}) with a long exponential backoff (starting
 * at 20s, up to 90s, up to 5 attempts) so these transient quota bursts are
 * absorbed automatically instead of failing the whole workflow node.
 *
 * <p>{@code agentic.llm.google-genai.include-thoughts=true} additionally
 * asks Gemini's "thinking" models to return "thought summaries" — a
 * running, unsure/interim reasoning trace distinct from the model's final
 * answer, conceptually the same two-tier distinction Copilot CLI itself
 * shows in its own reasoning output. {@code SpringAiLlmClient} separates
 * these from the final answer text and logs them distinctly. Only
 * Gemini's 2.5+/3.x "thinking" model family supports this (including the
 * default {@code gemini-flash-lite-latest}); the API rejects the request
 * outright if set on a non-thinking model like {@code gemini-2.0-flash}
 * (this project's production default), so it is opt-in per environment
 * rather than always-on.
 *
 * <p>Active only when both {@code agentic.llm.enabled=true} and
 * {@code agentic.llm.provider=google-genai} (the default). See
 * {@link LlmProperties}'s Javadoc for why this bean is built by hand
 * instead of relying on Spring AI's own starter autoconfiguration.
 */
@Configuration
@EnableConfigurationProperties(GoogleGenAiProperties.class)
@ConditionalOnProperty(prefix = "agentic.llm", name = "enabled", havingValue = "true")
public class GoogleGenAiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiAutoConfiguration.class);
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    @Bean
    @ConditionalOnProperty(prefix = "agentic.llm", name = "provider", havingValue = "google-genai", matchIfMissing = true)
    public ChatModel chatModel(GoogleGenAiProperties properties) {
        Client.Builder clientBuilder = Client.builder();
        if (properties.usesApiKey()) {
            clientBuilder.apiKey(properties.apiKey());
        } else {
            clientBuilder.vertexAI(true)
                    .project(properties.projectId())
                    .location(properties.location());
        }
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(properties.model())
                .includeThoughts(properties.includeThoughts())
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(clientBuilder.build())
                .options(options)
                .retryTemplate(retryTemplate())
                .build();
    }

    private static RetryTemplate retryTemplate() {
        RetryPolicy policy = RetryPolicy.builder()
                .predicate(GoogleGenAiAutoConfiguration::isRetryableQuotaError)
                .maxRetries(5)
                .delay(Duration.ofSeconds(20))
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(90))
                .build();
        RetryTemplate retryTemplate = new RetryTemplate(policy);
        // Spring's RetryTemplate only logs its own retry activity at DEBUG
        // (via an internal LogAccessor), so without this listener a 429
        // quota burst silently retries for minutes with zero visible log
        // output — indistinguishable from the workflow simply being
        // "stuck". Logging every attempt/exhaustion at WARN/INFO here
        // makes that retrying-in-the-background state visible as it
        // happens.
        retryTemplate.setRetryListener(new LoggingRetryListener());
        return retryTemplate;
    }

    /**
     * Logs every retry attempt and the final outcome (success/failure) at
     * WARN/INFO — see {@link #retryTemplate()} for why this is needed on
     * top of Spring's own (DEBUG-only) retry logging.
     */
    private static final class LoggingRetryListener implements RetryListener {
        @Override
        public void beforeRetry(RetryPolicy retryPolicy, org.springframework.core.retry.Retryable<?> retryable,
                org.springframework.core.retry.RetryState retryState) {
            log.warn("LLM call hit a retryable error (attempt {}) — retrying after backoff: {}",
                    retryState.getRetryCount(), describe(retryState.getLastException()));
        }

        @Override
        public void onRetrySuccess(RetryPolicy retryPolicy, org.springframework.core.retry.Retryable<?> retryable,
                Object result) {
            log.info("LLM call succeeded after retrying");
        }

        @Override
        public void onRetryPolicyExhaustion(RetryPolicy retryPolicy, org.springframework.core.retry.Retryable<?> retryable,
                RetryException retryException) {
            log.warn("LLM call gave up after exhausting all retries: {}", describe(retryException.getCause()));
        }

        private static String describe(Throwable throwable) {
            if (throwable == null) {
                return "unknown error";
            }
            return "%s: %s".formatted(throwable.getClass().getSimpleName(), throwable.getMessage());
        }
    }

    /**
     * {@link GoogleGenAiChatModel#getContentResponse} wraps the SDK's
     * {@link ApiException} in a plain {@code RuntimeException("Failed to
     * generate content", cause)} before this predicate ever sees it, so the
     * 429 must be found by walking the cause chain rather than checking the
     * outermost throwable directly.
     */
    private static boolean isRetryableQuotaError(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ApiException apiException && apiException.code() == HTTP_TOO_MANY_REQUESTS) {
                return true;
            }
        }
        return false;
    }
}
