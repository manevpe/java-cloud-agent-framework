package io.github.manevpe.agentic.integration.llm.tool;

import io.github.manevpe.agentic.integration.HumanInteractionClient;
import io.github.manevpe.agentic.integration.HumanInteractionClientRegistry;
import io.github.manevpe.agentic.plugin.PluginContext;
import io.github.manevpe.agentic.plugin.PluginContextAware;
import io.github.manevpe.agentic.tool.ToolBundle;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Optional;

/**
 * Lets an LLM pause its own turn to ask a human a clarifying question,
 * rather than being confined to a single request/response exchange — the
 * tool a {@code ConversationSession}-based agent (see {@code
 * ConversationalPlanningAgent}) uses instead of the fixed one-round {@code
 * human-gate}/{@code SlackGateAgent} node.
 *
 * <p>Which messaging provider/target this posts to is not hardcoded here —
 * it's configured per workflow node (e.g. {@code humanInteraction:
 * {provider: slack, target: '#dev-agent-plans'}}) and resolved via {@link
 * HumanInteractionClientRegistry}, the same generic port {@code
 * SlackGateAgent} could be swapped onto if a future messaging system is
 * added. The owning agent calls {@link #configureForCurrentCall} right
 * before invoking the LLM so this thread's {@code askHuman} tool call
 * knows which provider/target to use.
 *
 * <p>A tool call can't literally block the calling thread waiting for a
 * reply that might arrive minutes or days later — that would pin a
 * virtual thread and, worse, hold the whole workflow invocation open past
 * any sane HTTP timeout. Instead, calling this tool just starts the
 * question/thread and records it in a {@link ThreadLocal} (same per-call
 * tracking pattern as {@code WorkspaceSetupTool}'s opened-workspace
 * list — Spring AI invokes tool methods synchronously on the same thread
 * that called {@code LlmClient#complete}); the owning agent checks {@link
 * #consumePendingQuestion()} right after that call returns and, if
 * present, persists the conversation as {@code AWAITING_HUMAN} and pauses
 * the workflow node itself (see {@code AgentResult.WaitForEvent}). The
 * tool's own return value instructs the model not to produce anything
 * further this turn, since asking a question supersedes whatever else it
 * might otherwise have said.
 */
public class AskHumanTool implements ToolBundle, PluginContextAware {

    private HumanInteractionClientRegistry clientRegistry;
    private final ThreadLocal<CallContext> callContext = new ThreadLocal<>();
    private final ThreadLocal<PendingQuestion> pendingQuestion = new ThreadLocal<>();

    /** No-arg constructor for {@code ServiceLoader} discovery — see {@link #setPluginContext}. */
    public AskHumanTool() {
    }

    public AskHumanTool(HumanInteractionClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    @Override
    public void setPluginContext(PluginContext context) {
        this.clientRegistry = context.humanInteractionClientRegistry();
    }

    @Override
    public String name() {
        return "ask-human";
    }

    @Override
    public List<ToolCallback> tools() {
        return List.of(ToolCallbacks.from(this));
    }

    /**
     * Binds which provider/target this thread's next {@code askHuman}
     * tool call (if any) should use, read from the calling node's own
     * {@code humanInteraction} config — see {@code ConversationalPlanningAgent}.
     */
    public void configureForCurrentCall(String provider, String target) {
        callContext.set(new CallContext(provider, target));
    }

    /** Clears this thread's call context; called in a {@code finally} block by the owning agent. */
    public void clearCallContext() {
        callContext.remove();
    }

    @Tool(description = "Ask a human a clarifying question when you are not confident enough to proceed. "
            + "Calling this ends your turn immediately — do not produce any further tool calls or text after "
            + "calling it; you will receive the human's answer as a new turn in this same conversation once "
            + "they reply, and can then continue or ask a follow-up question.")
    public String askHuman(@ToolParam(description = "the clarifying question to ask") String question) {
        CallContext context = callContext.get();
        if (context == null) {
            throw new IllegalStateException(
                    "askHuman was called without configureForCurrentCall having been set on this thread first");
        }
        HumanInteractionClient client = clientRegistry.resolve(context.provider());
        String correlationKey = client.ask(context.target(), question);
        pendingQuestion.set(new PendingQuestion(question, correlationKey));
        return "Question posted; do not say or do anything else this turn — you will be resumed "
                + "automatically once a human replies.";
    }

    /**
     * Returns (and clears) the question asked during the current thread's
     * most recent {@code LlmClient#complete} call, if any. Called by the
     * owning agent right after that call returns.
     */
    public Optional<PendingQuestion> consumePendingQuestion() {
        PendingQuestion question = pendingQuestion.get();
        pendingQuestion.remove();
        return Optional.ofNullable(question);
    }

    private record CallContext(String provider, String target) {
    }

    /** @param correlationKey the id a later inbound reply resumes the paused workflow node with */
    public record PendingQuestion(String question, String correlationKey) {
    }
}
