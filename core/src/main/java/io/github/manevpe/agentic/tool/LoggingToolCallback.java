package io.github.manevpe.agentic.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps a {@link ToolCallback} so every invocation is logged at INFO —
 * name, truncated arguments, and either the truncated result or the
 * failure, plus duration. {@link SpringAiLlmClient}-equivalent logging
 * (in {@code SpringAiLlmClient}) only captures the prompt sent to the
 * model and its final text response; Spring AI's {@code
 * ToolCallingAdvisor} runs the actual tool-call loop internally, so
 * without this wrapper there is zero visibility into which tools a
 * model calls, in what order, or whether a call is hanging — a real gap
 * hit live while diagnosing a runaway-{@code gitClone} incident, where
 * the only way to tell whether the model was still active was an
 * unhelpful thread dump (virtual threads don't appear in a default
 * {@code SIGQUIT} dump).
 *
 * <p>{@link ToolRegistry#resolveTools} wraps every tool with this
 * decorator before returning them, so every agent gets this logging for
 * free without needing to know or care about it.
 */
public final class LoggingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(LoggingToolCallback.class);

    /** Keeps a runaway tool argument/result from flooding the log. */
    private static final int MAX_LOGGED_CHARS = 2_000;

    private final ToolCallback delegate;

    public LoggingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return timedCall(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return timedCall(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String timedCall(String toolInput, java.util.function.Supplier<String> invocation) {
        String name = delegate.getToolDefinition().name();
        log.info("Tool call started: {}({})", name, truncate(toolInput));
        long startNanos = System.nanoTime();
        try {
            String result = invocation.get();
            log.info("Tool call finished: {} in {}ms -> {}",
                    name, elapsedMillis(startNanos), truncate(result));
            return result;
        } catch (RuntimeException e) {
            log.info("Tool call failed: {} in {}ms -> {}: {}",
                    name, elapsedMillis(startNanos), e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= MAX_LOGGED_CHARS ? value : value.substring(0, MAX_LOGGED_CHARS) + "...(truncated)";
    }
}
