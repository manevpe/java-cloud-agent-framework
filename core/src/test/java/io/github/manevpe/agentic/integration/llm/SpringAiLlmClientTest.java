package io.github.manevpe.agentic.integration.llm;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link SpringAiLlmClient}'s trace-scoped request logging: the
 * first call for a given {@code traceId} logs the full prompt, and later
 * calls in the same trace log only the newest content inserted into an
 * otherwise-unchanged prompt (see PlanningAgent/ConversationalPlanningAgent's
 * growing clarification-history/transcript block) — never re-logging the
 * large static context (ticket summary/description, knowledge-source
 * content) surrounding it.
 */
class SpringAiLlmClientTest {

    private ChatModel chatModel;
    private SpringAiLlmClient client;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        client = new SpringAiLlmClient(ChatClient.builder(chatModel));

        Logger logbackLogger = (Logger) org.slf4j.LoggerFactory.getLogger(SpringAiLlmClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logbackLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        MDC.remove("traceId");
        Logger logbackLogger = (Logger) org.slf4j.LoggerFactory.getLogger(SpringAiLlmClient.class);
        logbackLogger.detachAppender(logAppender);
    }

    @Test
    void firstCallInATraceLogsTheFullPrompt() {
        MDC.put("traceId", "trace-1");
        stubResponse("first response");

        client.complete("system prompt", "Ticket: T-1\nKnowledge:\n- item one\n\nHistory:\n(none yet)", List.of());

        String logged = joinedLogMessages();
        assertThat(logged).contains("first call");
        assertThat(logged).contains("system prompt");
        assertThat(logged).contains("item one");
        assertThat(logged).contains("(none yet)");
    }

    @Test
    void secondCallInSameTraceOnlyLogsTheNewlyInsertedContent() {
        MDC.put("traceId", "trace-1");
        stubResponse("first response");
        client.complete("system prompt",
                "Ticket: T-1\nKnowledge:\n- a big chunk of imported knowledge-source context\n\n"
                        + "History:\n(none yet)\n\nAlways follow instructions.",
                List.of());
        logAppender.list.clear();

        stubResponse("second response");
        client.complete("system prompt",
                "Ticket: T-1\nKnowledge:\n- a big chunk of imported knowledge-source context\n\n"
                        + "History:\nQ: what should X do? A: do Y\n\nAlways follow instructions.",
                List.of());

        String logged = joinedLogMessages();
        // The newest round's Q&A must be logged...
        assertThat(logged).contains("Q: what should X do? A: do Y");
        // ...but the large static context shared with the previous call must not be repeated.
        assertThat(logged).doesNotContain("a big chunk of imported knowledge-source context");
        // Nor the static trailing instructions after the growing history block.
        assertThat(logged).doesNotContain("Always follow instructions.");
    }

    @Test
    void identicalConsecutivePromptsInSameTraceLogNoPromptBody() {
        MDC.put("traceId", "trace-1");
        stubResponse("first response");
        client.complete("system prompt", "same user prompt", List.of());
        logAppender.list.clear();

        stubResponse("second response");
        client.complete("system prompt", "same user prompt", List.of());

        String logged = joinedLogMessages();
        assertThat(logged).contains("identical prompt");
        assertThat(logged).doesNotContain("same user prompt");
    }

    @Test
    void differentTracesAreLoggedIndependently() {
        MDC.put("traceId", "trace-a");
        stubResponse("response a");
        client.complete("system prompt", "prompt for trace a", List.of());

        MDC.put("traceId", "trace-b");
        stubResponse("response b");
        client.complete("system prompt", "prompt for trace b", List.of());

        String logged = joinedLogMessages();
        assertThat(logged).contains("first call");
        assertThat(logged).contains("prompt for trace a");
        assertThat(logged).contains("prompt for trace b");
    }

    @Test
    void responseIsAlwaysLoggedInFull() {
        MDC.put("traceId", "trace-1");
        stubResponse("the model's full reasoning and answer");

        client.complete("system prompt", "user prompt", List.of());

        assertThat(joinedLogMessages()).contains("the model's full reasoning and answer");
    }

    @Test
    void thoughtSummaryPartsAreLoggedSeparatelyAndExcludedFromTheReturnedAnswer() {
        MDC.put("traceId", "trace-1");
        ChatResponse response = new ChatResponse(
                List.of(
                        new Generation(assistantMessage("Hmm, maybe I should check X first...", true)),
                        new Generation(assistantMessage("Final answer: do Y.", false))),
                ChatResponseMetadata.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        String result = client.complete("system prompt", "user prompt", List.of());

        assertThat(result).isEqualTo("Final answer: do Y.");
        String logged = joinedLogMessages();
        assertThat(logged).contains("LLM reasoning (interim, not the final answer)");
        assertThat(logged).contains("Hmm, maybe I should check X first...");
    }

    @Test
    void withoutThoughtMetadataResponseBehavesAsBefore() {
        MDC.put("traceId", "trace-1");
        stubResponse("plain response, no thought metadata at all");

        String result = client.complete("system prompt", "user prompt", List.of());

        assertThat(result).isEqualTo("plain response, no thought metadata at all");
        assertThat(joinedLogMessages()).doesNotContain("LLM reasoning (interim, not the final answer)");
    }

    private static AssistantMessage assistantMessage(String content, boolean isThought) {
        return AssistantMessage.builder()
                .content(content)
                .properties(java.util.Map.of("isThought", isThought))
                .build();
    }

    private void stubResponse(String content) {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage(content))),
                ChatResponseMetadata.builder().build());
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(captor.capture())).thenReturn(response);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    private String joinedLogMessages() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
