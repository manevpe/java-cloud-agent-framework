package io.github.manevpe.agentic.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Works around a design flaw shared by every Spring AI chat model integration
 * (observed concretely with {@code spring-ai-google-genai}, but not specific
 * to it): {@link org.springframework.ai.model.tool.DefaultToolCallingManager}
 * only ever JSON-encodes a tool's <em>successful</em> return value (via
 * {@code DefaultToolCallResultConverter}, which uses {@code JsonHelper}) —
 * but when a {@code @Tool}-annotated method throws, the manager instead calls
 * {@link ToolExecutionExceptionProcessor#process(ToolExecutionException)} and
 * stores whatever plain-text message that returns directly as the {@code
 * ToolResponseMessage}'s {@code responseData}, completely bypassing that JSON
 * encoding step.
 *
 * <p>Since {@code responseData} is expected to always be valid JSON (it gets
 * re-parsed via {@code readValue} — not {@code readTree} — the next time the
 * conversation history is replayed to the model), any plain, non-JSON error
 * message written this way corrupts the very next request with a cryptic
 * {@code RuntimeException: Failed to parse JSON: <message>}. This has been
 * observed in this application whenever a tool call fails during the coding
 * agent's extended tool-calling loop (e.g. a sandbox workspace or shell
 * command error), regardless of which specific exception or message text was
 * involved — the root cause is structural, not tied to one tool.
 *
 * <p>The fix: supply our own {@link ToolExecutionExceptionProcessor} bean
 * (Spring AI's own bean is {@code @ConditionalOnMissingBean}) that delegates
 * to the default message-selection logic, then JSON-encodes the result before
 * returning it, guaranteeing {@code responseData} is always valid JSON on
 * this path too.
 */
@Configuration
public class ToolExecutionErrorHandlingConfiguration {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        ToolExecutionExceptionProcessor delegate = DefaultToolExecutionExceptionProcessor.builder().build();
        return exception -> {
            String message = delegate.process(exception);
            try {
                return MAPPER.writeValueAsString(message);
            } catch (Exception jsonException) {
                // Should never happen for a plain String, but fall back to a
                // hand-quoted literal rather than ever forwarding raw text.
                return "\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
        };
    }
}
