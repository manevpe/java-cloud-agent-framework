package io.github.manevpe.agentic.agent.planning;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.conversation.ConversationRole;
import io.github.manevpe.agentic.conversation.ConversationSession;
import io.github.manevpe.agentic.conversation.ConversationStatus;
import io.github.manevpe.agentic.conversation.ConversationTurn;
import io.github.manevpe.agentic.integration.LlmClient;
import io.github.manevpe.agentic.integration.llm.tool.AskHumanTool;
import io.github.manevpe.agentic.integration.llm.tool.FileReadTool;
import io.github.manevpe.agentic.integration.llm.tool.WorkspaceSetupTool;
import io.github.manevpe.agentic.persistence.ConversationSessionRepository;
import io.github.manevpe.agentic.plugin.PluginContext;
import io.github.manevpe.agentic.plugin.PluginContextAware;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Drafts an implementation plan the same way {@link PlanningAgent} does,
 * but as a persistent {@link ConversationSession} the LLM can pause and
 * resume as many times as it needs via the {@code askHuman} tool, rather
 * than the fixed single {@code human-gate} round — the "cloud Copilot
 * CLI"-style alternative described in ADR-0003. Registered under {@code
 * conversational-planning-agent} in workflow YAML; see {@code
 * jira-to-pr-conversational.yaml} for a full example alongside {@code
 * jira-to-pr.yaml}'s {@code planning-agent}/{@code human-gate} pair.
 *
 * <p>Per the LangGraph4j constraint documented on {@code
 * WorkflowGraphFactory} (a node can't both be an {@code interruptsAfter}
 * point and conditionally continue), this agent never pauses itself: it
 * always returns {@link AgentResult.Continue}, setting {@code
 * conversationStatus}/{@code humanQuestionCorrelationKey} in state when it
 * wants to pause. The actual pause point is the dedicated {@code
 * ConversationResumeGateAgent} node the routing sends control to next (see
 * {@code conversationAwaitingHuman} edge condition in {@code
 * WorkflowConditions}), which loops back into this node once a reply
 * arrives.
 *
 * <p>Which messaging provider/target {@code askHuman} posts to is read
 * from this node's own {@code humanInteraction: {provider, target}} YAML
 * config (never hardcoded or globally configured — see {@link
 * AskHumanTool}'s Javadoc) and pushed onto the tool via {@link
 * AskHumanTool#configureForCurrentCall} right before every {@link
 * LlmClient#complete} call.
 */
public class ConversationalPlanningAgent implements Agent, PluginContextAware {

    private static final Logger log = LoggerFactory.getLogger(ConversationalPlanningAgent.class);
    private static final int DEFAULT_MAX_CONVERSATION_ROUNDS = 12;
    private static final String DEFAULT_HUMAN_PROVIDER = "slack";
    private static final String DEFAULT_HUMAN_TARGET = "#dev-agent-plans";
    private static final List<String> REQUIRED_TOOLS = List.of("file-read", "workspace-setup", "ask-human");

    private LlmClient llmClient;
    private ConversationSessionRepository sessionRepository;
    private AskHumanTool askHumanTool;
    private WorkspaceSetupTool workspaceSetupTool;
    private String systemPrompt;
    private String userPromptTemplate;
    private List<ToolCallback> tools;

    public ConversationalPlanningAgent() {
    }

    @Override
    public List<String> requiredTools() {
        return REQUIRED_TOOLS;
    }

    @Override
    public void setPluginContext(PluginContext context) {
        this.llmClient = context.llmClient();
        this.sessionRepository = context.conversationSessionRepository();
        this.askHumanTool = context.toolRegistry().resolveInstance("ask-human", AskHumanTool.class);
        this.workspaceSetupTool = context.toolRegistry().resolveInstance("workspace-setup", WorkspaceSetupTool.class);
        String systemPromptLocation = context.environment().getProperty(
                "agentic.llm.conversational-planning.system-prompt-location",
                "classpath:prompts/conversational-planning-system-prompt.st");
        String userPromptLocation = context.environment().getProperty(
                "agentic.llm.conversational-planning.user-prompt-location",
                "classpath:prompts/conversational-planning-user-prompt.st");
        this.systemPrompt = readResource(context.resourceLoader().getResource(systemPromptLocation));
        this.userPromptTemplate = readResource(context.resourceLoader().getResource(userPromptLocation));
        this.tools = context.toolRegistry().resolveTools(REQUIRED_TOOLS);
    }

    @Override
    public String type() {
        return "conversational-planning-agent";
    }

    @Override
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        String ticketKey = state.get("ticketKey", String.class).orElse("UNKNOWN");
        String summary = state.get("summary", String.class).orElse("");
        String description = state.get("description", String.class).orElse("");
        int maxRounds = ((Number) node.config().getOrDefault(
                "maxConversationRounds", DEFAULT_MAX_CONVERSATION_ROUNDS)).intValue();
        Map<String, Object> humanInteraction = readHumanInteractionConfig(node);
        String provider = String.valueOf(humanInteraction.getOrDefault("provider", DEFAULT_HUMAN_PROVIDER));
        String target = String.valueOf(humanInteraction.getOrDefault("target", DEFAULT_HUMAN_TARGET));

        ConversationSession session = loadOrStartSession(state, ticketKey);
        String humanReply = state.get("humanReply", String.class).orElse(null);
        if (session.status() == ConversationStatus.AWAITING_HUMAN && humanReply != null && !humanReply.isBlank()) {
            session = session.withAppendedTurn(ConversationTurn.of(ConversationRole.HUMAN, humanReply)).resumed();
        }

        boolean roundsExhausted = session.humanReplyCount() >= maxRounds;
        List<String> knowledge = knowledgeContextFrom(state);
        String userPrompt = buildUserPrompt(ticketKey, summary, description, knowledge, session, roundsExhausted);

        askHumanTool.configureForCurrentCall(provider, target);
        String response;
        try {
            response = llmClient.complete(systemPrompt, userPrompt, tools);
        } finally {
            askHumanTool.clearCallContext();
            // Workspaces opened via gitClone during this call are scoped to a
            // single conversation turn, same as PlanningAgent — never kept
            // alive across the async gap while paused on a human's reply.
            workspaceSetupTool.closeAllOpenedInCurrentCall();
        }

        Optional<AskHumanTool.PendingQuestion> pendingQuestion = askHumanTool.consumePendingQuestion();
        if (pendingQuestion.isPresent() && !roundsExhausted) {
            session = session.withAppendedTurn(ConversationTurn.of(ConversationRole.ASSISTANT, pendingQuestion.get().question()))
                    .awaitingHuman(pendingQuestion.get().correlationKey());
            sessionRepository.save(session);

            state.put("conversationSessionId", session.id().toString());
            state.put("conversationStatus", ConversationStatus.AWAITING_HUMAN.name());
            state.put("humanQuestionCorrelationKey", pendingQuestion.get().correlationKey());
            return new AgentResult.Continue(state);
        }

        String planText = response == null || response.isBlank()
                ? heuristicPlan(ticketKey, summary, description, knowledge)
                : finalizePlanText(response, pendingQuestion, roundsExhausted);

        session = session.withAppendedTurn(ConversationTurn.of(ConversationRole.ASSISTANT, planText)).completed();
        sessionRepository.save(session);

        state.put("conversationSessionId", session.id().toString());
        state.put("conversationStatus", ConversationStatus.COMPLETED.name());
        state.put("plan", planText);
        state.put("finalPlan", planText);
        state.put("hasOpenQuestions", false);
        return new AgentResult.Continue(state);
    }

    private ConversationSession loadOrStartSession(WorkflowState state, String ticketKey) {
        Optional<String> existingId = state.get("conversationSessionId", String.class);
        if (existingId.isPresent()) {
            return sessionRepository.findById(UUID.fromString(existingId.get()))
                    .orElseThrow(() -> new IllegalStateException(
                            "No ConversationSession found for id " + existingId.get()));
        }
        return ConversationSession.start(ticketKey, type(), List.of());
    }

    /**
     * Reads this node's own resolved knowledge context, populated
     * generically by the engine (see {@code WorkflowNodeAction}) before
     * this agent ran, from its {@code knowledgeSources: [...]} YAML
     * config — an empty list means this node had no sources configured,
     * by design; there is no implicit "query everything" fallback. Same
     * pattern as {@code PlanningAgent}/{@code CodingAgent}.
     */
    @SuppressWarnings("unchecked")
    private static List<String> knowledgeContextFrom(WorkflowState state) {
        return (List<String>) (List<?>) state.get(WorkflowState.KNOWLEDGE_CONTEXT, List.class).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readHumanInteractionConfig(NodeDefinition node) {
        Object raw = node.config().get("humanInteraction");
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String buildUserPrompt(
            String ticketKey, String summary, String description, List<String> knowledge,
            ConversationSession session, boolean roundsExhausted) {
        String transcript = renderTranscript(session);
        String prompt = userPromptTemplate
                .replace("{{ticketKey}}", ticketKey)
                .replace("{{summary}}", summary)
                .replace("{{description}}", description)
                .replace("{{knowledge}}", String.join("\n- ", knowledge))
                .replace("{{transcript}}", transcript);
        if (roundsExhausted) {
            prompt += "\n\nNOTE: You have reached the maximum number of clarification rounds. Do not call "
                    + "askHuman again — produce the final JSON plan now, noting any remaining uncertainty "
                    + "directly inside \"planText\".";
        }
        return prompt;
    }

    private static String renderTranscript(ConversationSession session) {
        if (session.turns().isEmpty()) {
            return "(conversation not started yet)";
        }
        StringBuilder sb = new StringBuilder();
        for (ConversationTurn turn : session.turns()) {
            sb.append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        return sb.toString();
    }

    /**
     * The "ready" response is just the whole completion text — no JSON,
     * no marker line needed, since {@code askHuman} already fully covers
     * the "not ready yet" case (see {@link #execute}'s early return once
     * {@link AskHumanTool#consumePendingQuestion()} is present) and this
     * plan is a single free-text blob with no other field to keep
     * separate from it.
     */
    private static String finalizePlanText(
            String response, Optional<AskHumanTool.PendingQuestion> pendingQuestion, boolean roundsExhausted) {
        if (roundsExhausted && pendingQuestion.isPresent()) {
            // Model tried to ask another question despite being told rounds
            // were exhausted (see buildUserPrompt's NOTE) — force a plan
            // anyway rather than looping forever, noting what's unresolved.
            log.warn("LLM asked another question after max conversation rounds were reached; forcing plan"
                    + " with a note about the unresolved question: {}", pendingQuestion.get().question());
            return "Plan incomplete: maximum clarification rounds reached with an unresolved question:\n- "
                    + pendingQuestion.get().question();
        }
        return response.strip();
    }

    /** Mirrors {@code PlanningAgent}'s no-LLM-configured fallback — no askHuman loop is possible without a model. */
    private static String heuristicPlan(String ticketKey, String summary, String description, List<String> knowledge) {
        return """
                Plan for %s: %s

                Requirements:
                %s

                Relevant knowledge:
                - %s
                """.formatted(ticketKey, summary, description, String.join("\n- ", knowledge));
    }

    private static String readResource(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read prompt template from " + resource, e);
        }
    }
}
